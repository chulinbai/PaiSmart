package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHandlerFinishReasonFlowTest {

    private static final String USER_ID = "1";
    private static final String CONVERSATION_ID = "conversation-1";
    private static final String GENERATION_ID = "generation-1";

    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private LlmProviderRouter llmProviderRouter;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private ChatGenerationStateService generationStateService;
    @Mock
    private ChatSessionRegistry chatSessionRegistry;
    @Mock
    private AgentToolRegistry agentToolRegistry;
    @Mock
    private AgentContextService agentContextService;
    @Mock
    private DeepSeekClient deepSeekClient;
    @Mock
    private ThreadPoolTaskExecutor chatMonitorExecutor;

    private ChatHandler chatHandler;

    @BeforeEach
    void setUp() {
        chatHandler = new ChatHandler(
                redisTemplate,
                llmProviderRouter,
                rateLimitService,
                conversationService,
                generationStateService,
                chatSessionRegistry,
                agentToolRegistry,
                agentContextService,
                deepSeekClient,
                new ObjectMapper(),
                chatMonitorExecutor
        );
    }

    @Test
    void continuesOnceAndCompletesWhenContinuationStops() throws Exception {
        prepareResponseBuilder("first");
        stubContinuation("continued", "stop");
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        chatHandler.finishFinalTurn(
                USER_ID, "question", CONVERSATION_ID, GENERATION_ID, responseFuture,
                new ArrayList<>(), turn("first", "length"), 10, 20
        );

        assertEquals("firstcontinued", responseFuture.join());
        verify(generationStateService).markCompleted(
                eq(GENERATION_ID), anyMap(), eq("stop"), eq(false), eq(1));
        verify(conversationService).recordConversation(
                eq(1L), eq("question"), eq("firstcontinued"), eq(CONVERSATION_ID), any());
    }

    @Test
    void marksCompletedAnswerAsTruncatedWhenContinuationAlsoHitsLength() throws Exception {
        prepareResponseBuilder("first");
        stubContinuation("continued", "length");
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        chatHandler.finishFinalTurn(
                USER_ID, "question", CONVERSATION_ID, GENERATION_ID, responseFuture,
                new ArrayList<>(), turn("first", "length"), 10, 20
        );

        assertEquals("firstcontinued", responseFuture.join());
        verify(generationStateService).markCompleted(
                eq(GENERATION_ID), anyMap(), eq("length"), eq(true), eq(1));
    }

    @Test
    void safetyFinishReasonFailsWithoutPersistingConversation() throws Exception {
        prepareResponseBuilder("partial");
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        chatHandler.finishFinalTurn(
                USER_ID, "question", CONVERSATION_ID, GENERATION_ID, responseFuture,
                new ArrayList<>(), turn("partial", "content_filter"), 10, 20
        );

        assertTrue(responseFuture.isCompletedExceptionally());
        verify(generationStateService).markFailed(
                eq(GENERATION_ID), any(), eq("content_filter"), eq(false), eq(0));
        verify(conversationService, never()).recordConversation(any(), any(), any(), any(), any());
        verify(llmProviderRouter, never()).streamReActTurn(
                any(), anyList(), anyList(), anyInt(), any(), any(), any());
    }

    private void stubContinuation(String chunk, String finishReason) throws Exception {
        Constructor<LlmProviderRouter.StreamHandle> constructor = LlmProviderRouter.StreamHandle.class
                .getDeclaredConstructor(reactor.core.Disposable.class, Runnable.class);
        constructor.setAccessible(true);
        LlmProviderRouter.StreamHandle handle = constructor.newInstance(null, null);
        when(llmProviderRouter.streamReActTurn(
                eq(USER_ID), anyList(), anyList(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> onChunk = invocation.getArgument(4);
                    Consumer<LlmProviderRouter.ReActTurn> onComplete = invocation.getArgument(6);
                    onChunk.accept(chunk);
                    onComplete.accept(turn(chunk, finishReason));
                    return handle;
                });
    }

    private LlmProviderRouter.ReActTurn turn(String content, String finishReason) {
        return new LlmProviderRouter.ReActTurn(
                content,
                List.of(),
                Map.of("role", "assistant", "content", content),
                finishReason,
                3,
                4
        );
    }

    @SuppressWarnings("unchecked")
    private void prepareResponseBuilder(String content) throws Exception {
        Field field = ChatHandler.class.getDeclaredField("responseBuilders");
        field.setAccessible(true);
        Map<String, StringBuilder> builders = (Map<String, StringBuilder>) field.get(chatHandler);
        builders.put(GENERATION_ID, new StringBuilder(content));
    }
}
