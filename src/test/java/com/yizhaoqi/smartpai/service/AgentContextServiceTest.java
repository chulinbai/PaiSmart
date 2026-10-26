package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.AgentWorkspace;
import com.yizhaoqi.smartpai.model.ContextEvent;
import com.yizhaoqi.smartpai.model.ConversationSession;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AgentContextServiceTest {
    private ContextEventRepository eventRepository;
    private AgentContextService service;

    @BeforeEach
    void setUp() {
        AgentWorkspaceRepository workspaceRepository = mock(AgentWorkspaceRepository.class);
        ConversationSessionRepository sessionRepository = mock(ConversationSessionRepository.class);
        ConversationRepository conversationRepository = mock(ConversationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        eventRepository = mock(ContextEventRepository.class);
        ContextSnapshotRepository snapshotRepository = mock(ContextSnapshotRepository.class);
        ContextMemoryRepository memoryRepository = mock(ContextMemoryRepository.class);
        AgentRunRepository runRepository = mock(AgentRunRepository.class);
        ContextArtifactService artifactService = mock(ContextArtifactService.class);
        ModelProviderConfigService providerConfigService = mock(ModelProviderConfigService.class);

        User user = new User();
        user.setId(1L);
        ConversationSession session = new ConversationSession();
        session.setUser(user);
        session.setConversationId("thread-1");
        session.setWorkspaceId("workspace-1");
        AgentWorkspace workspace = new AgentWorkspace();
        workspace.setId("workspace-1");
        workspace.setOwnerUserId(1L);

        when(sessionRepository.findByConversationId("thread-1")).thenReturn(Optional.of(session));
        when(workspaceRepository.findByIdAndOwnerUserId("workspace-1", 1L)).thenReturn(Optional.of(workspace));
        when(conversationRepository.findByConversationIdOrderByTimestampAsc("thread-1")).thenReturn(List.of());
        when(snapshotRepository.findTopByThreadIdOrderByVersionDesc("thread-1")).thenReturn(Optional.empty());
        when(memoryRepository.findByOwnerUserIdAndActiveTrueOrderByUpdatedAtAsc(1L)).thenReturn(List.of());
        when(eventRepository.findTopByThreadIdAndEventTypeOrderBySequenceDesc(
                "thread-1", ContextEvent.EventType.RESET_BOUNDARY)).thenReturn(Optional.empty());
        when(providerConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM)).thenReturn(
                new ModelProviderConfigService.ActiveProviderView(
                        "test", "Test", "openai-compatible", "http://localhost", "test", "key", null, 4096));

        TokenCounter counter = new TokenCounter() {
            public int countText(String text) { return text == null ? 0 : text.length(); }
            public int countMessages(List<Map<String, Object>> messages) {
                return messages.stream().mapToInt(m -> countText(String.valueOf(m.getOrDefault("content", ""))) + 4).sum();
            }
        };
        service = new AgentContextService(workspaceRepository, sessionRepository, conversationRepository,
                userRepository, eventRepository, snapshotRepository, memoryRepository, runRepository,
                artifactService, counter, providerConfigService, mock(LlmProviderRouter.class), new ObjectMapper());
    }

    @Test
    void rejectsWhenRequiredCurrentInputExceedsPromptBudget() {
        ContextEvent current = event(20, "user", "x".repeat(1200), 1200);
        when(eventRepository.findByThreadIdAndSequenceGreaterThanOrderBySequenceAsc("thread-1", 0L))
                .thenReturn(List.of(current));
        when(eventRepository.findByThreadIdOrderBySequenceAsc("thread-1")).thenReturn(List.of(current));

        assertThrows(AgentContextService.ContextOverflowException.class,
                () -> service.buildContext(1L, "thread-1", "system", List.of(), 2000));
    }

    @Test
    void loadsOnlyEventsAfterResetBoundary() {
        ContextEvent reset = event(10, "system", "reset", 1);
        when(eventRepository.findTopByThreadIdAndEventTypeOrderBySequenceDesc(
                "thread-1", ContextEvent.EventType.RESET_BOUNDARY)).thenReturn(Optional.of(reset));
        when(eventRepository.findByThreadIdAndSequenceGreaterThanOrderBySequenceAsc("thread-1", 10L))
                .thenReturn(List.of(event(11, "user", "current", 7)));
        when(eventRepository.findByThreadIdOrderBySequenceAsc("thread-1"))
                .thenReturn(List.of(reset, event(11, "user", "current", 7)));

        service.buildContext(1L, "thread-1", "system", List.of(), 2000);

        verify(eventRepository).findByThreadIdOrderBySequenceAsc("thread-1");
    }
    @Test
    void roundTrimmingPreservesOriginalQuestionAndRemovesCompleteToolGroups() {
        Map<String,Object> question=Map.of("role","user","content","original question");
        var messages=new java.util.ArrayList<Map<String,Object>>(List.of(
                Map.of("role","system","content","instructions"),question,
                Map.of("role","assistant","content","x".repeat(700),"tool_calls",List.of(Map.of("id","call"))),
                Map.of("role","tool","content","y".repeat(700),"tool_call_id","call"),
                Map.of("role","user","content","budget exhausted"),Map.of("role","user","content","final evidence")));
        service.fitRound(messages,List.of(),2000,question);
        org.junit.jupiter.api.Assertions.assertTrue(messages.contains(question));
        org.junit.jupiter.api.Assertions.assertFalse(messages.stream().anyMatch(m->m.containsKey("tool_calls") || "tool".equals(m.get("role"))));
        org.junit.jupiter.api.Assertions.assertEquals(4,messages.size());
    }

    private ContextEvent event(long sequence, String role, String content, int tokens) {
        ContextEvent event = new ContextEvent();
        event.setId(sequence);
        event.setSequence(sequence);
        event.setThreadId("thread-1");
        event.setWorkspaceId("workspace-1");
        event.setEventType(ContextEvent.EventType.MESSAGE);
        event.setRole(role);
        event.setContent(content);
        event.setTokenEstimate(tokens);
        return event;
    }
}
