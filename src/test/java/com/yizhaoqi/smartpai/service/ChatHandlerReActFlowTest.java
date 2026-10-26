package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.service.knowledge.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerReActFlowTest {
    ChatHandler handler;LlmProviderRouter router;AgentToolRegistry tools;ChatGenerationStateService generations;
    ThreadPoolTaskExecutor workers;AgentContextService contexts;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        router=mock(LlmProviderRouter.class);tools=mock(AgentToolRegistry.class);generations=mock(ChatGenerationStateService.class);contexts=mock(AgentContextService.class);
        handler=new ChatHandler(mock(RedisTemplate.class,RETURNS_DEEP_STUBS),router,mock(RateLimitService.class),mock(ConversationService.class),generations,
            mock(ChatSessionRegistry.class),tools,contexts,mock(DeepSeekClient.class),new ObjectMapper(),mock(ThreadPoolTaskExecutor.class));
        var retrieval=mock(KnowledgeRetrievalService.class);when(retrieval.validAll(anyCollection(),anyString())).thenReturn(true);
        ReflectionTestUtils.setField(handler,"knowledgeCoordinator",new KnowledgeRetrievalCoordinator(retrieval,new KnowledgeRagProperties()));
        workers=new ThreadPoolTaskExecutor();workers.setCorePoolSize(2);workers.setMaxPoolSize(4);workers.setQueueCapacity(16);workers.initialize();
        ReflectionTestUtils.setField(handler,"knowledgeRetrievalExecutor",workers);
        ((Map<String,StringBuilder>)ReflectionTestUtils.getField(handler,"responseBuilders")).put("g",new StringBuilder());
        ((Map<String,AgentContextService.ContextBundle>)ReflectionTestUtils.getField(handler,"generationContexts")).put("g",
            new AgentContextService.ContextBundle(List.of(Map.of("role","system","content","rules"),Map.of("role","user","content","question")),32000,24000,10,0,Map.of(),0,List.of(),false,List.of(),false,false));
        when(tools.getTools()).thenReturn(List.of(new AgentToolRegistry.AgentTool("search_knowledge","chunks",Map.of()),new AgentToolRegistry.AgentTool("search_graph","graph",Map.of())));
    }
    @AfterEach void cleanup() { workers.shutdown(); }
    LlmProviderRouter.ToolCallDecision call(String id,String name,String query) { return new LlmProviderRouter.ToolCallDecision(id,name,Map.of("query",query)); }
    LlmProviderRouter.ReActTurn turn(String text,List<LlmProviderRouter.ToolCallDecision> calls) {
        var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",text);
        if(!calls.isEmpty()) message.put("tool_calls",calls.stream().map(c->Map.of("id",c.id(),"type","function","function",Map.of("name",c.name(),"arguments","{}"))).toList());
        return new LlmProviderRouter.ReActTurn(text,calls,message,calls.isEmpty()?"stop":"tool_calls",10,10);
    }
    LlmProviderRouter.StreamHandle handle() throws Exception {
        var ctor=LlmProviderRouter.StreamHandle.class.getDeclaredConstructor(reactor.core.Disposable.class,Runnable.class);ctor.setAccessible(true);return ctor.newInstance(null,null);
    }
    AgentToolRegistry.ToolExecutionResult result(String name,String key,String type) {
        var source=SourceReference.of(1,10,new Chunk(key,"TEXT_CHUNK","source "+key,1,1,null,null,null,null,null,null,null,null),"md5","test.pdf");
        var evidence=new Evidence(type+key,type,"fact "+key,1,name,1,List.of(source),Map.of("scopeId","s"));
        return new AgentToolRegistry.ToolExecutionResult(name,true,"",Map.of(),false,new RetrievalResult(Status.SUCCESS,List.of(evidence),new Coverage(1,1,0,0),Map.of(),1,List.of()));
    }
    void run(CompletableFuture<String> future) { ReflectionTestUtils.invokeMethod(handler,"runReActLoop","1","question","c","g",future); }
    @Test void parallelToolsAndSecondRoundKeepCitationsAndOnlyFinalAnswerIsStreamed() throws Exception {
        var started=new CountDownLatch(2);
        when(tools.executeTool(anyString(),anyMap(),eq("1"))).thenAnswer(i->{
            String name=i.getArgument(0);Map<String,Object> args=i.getArgument(1);
            if("first".equals(args.get("query"))) { started.countDown();assertTrue(started.await(3,TimeUnit.SECONDS),"Both read-only tools must start independently"); }
            return result(name,"second".equals(args.get("query"))?"b":"a","search_graph".equals(name)?"GRAPH_RELATION":"TEXT_CHUNK");
        });
        var turns=List.of(turn("hidden candidate",List.of(call("1","search_knowledge","first"),call("2","search_graph","first"))),
            turn("hidden second",List.of(call("3","search_knowledge","second"))),turn("hidden draft",List.of()),turn("answer [1] [2]",List.of()));
        AtomicInteger round=new AtomicInteger();var handle=handle();List<List<Map<String,Object>>> prompts=new ArrayList<>();
        when(router.streamReActTurn(eq("1"),anyList(),anyList(),anyInt(),any(),any(),any())).thenAnswer(i->{
            List<Map<String,Object>> messages=i.getArgument(1);prompts.add(new ArrayList<>(messages));
            var response=turns.get(round.getAndIncrement());Consumer<String> onChunk=i.getArgument(4);Consumer<LlmProviderRouter.ReActTurn> done=i.getArgument(6);
            onChunk.accept(response.content());done.accept(response);return handle;
        });
        var future=new CompletableFuture<String>();run(future);
        assertEquals("answer [1] [2]",future.join());assertEquals(4,round.get());
        assertEquals(2,prompts.get(1).stream().filter(m->"tool".equals(m.get("role"))).count());
        assertTrue(prompts.get(2).stream().anyMatch(m->"tool".equals(m.get("role")) && String.valueOf(m.get("content")).contains("[2]")));
        verify(generations).markCompleted(eq("g"),argThat(mapping->mapping.size()==2 && "a".equals(String.valueOf(mapping.get("1").get("matchedChunkText")).replace("source ",""))),eq("stop"),eq(false),eq(0));
        verify(contexts,times(4)).fitRound(anyList(),anyList(),anyInt(),any());
    }
    @Test void toolBudgetStopsAtEightAndFourDecisionRounds() throws Exception {
        when(tools.executeTool(anyString(),anyMap(),eq("1"))).thenAnswer(i->result(i.getArgument(0),String.valueOf(((Map<?,?>)i.getArgument(1)).get("query")),"TEXT_CHUNK"));
        AtomicInteger round=new AtomicInteger();var handle=handle();
        when(router.streamReActTurn(eq("1"),anyList(),anyList(),anyInt(),any(),any(),any())).thenAnswer(i->{
            int r=round.incrementAndGet();List<?> available=i.getArgument(2);
            var response=available.isEmpty()?turn("final",List.of()):turn("candidate",List.of(call(r+"a","search_knowledge",r+"a"),call(r+"b","search_graph",r+"b"),call(r+"c","search_knowledge",r+"c")));
            ((Consumer<String>)i.getArgument(4)).accept(response.content());((Consumer<LlmProviderRouter.ReActTurn>)i.getArgument(6)).accept(response);return handle;
        });
        var future=new CompletableFuture<String>();run(future);assertEquals("final",future.join());assertEquals(5,round.get());
        verify(tools,times(8)).executeTool(anyString(),anyMap(),eq("1"));
    }
    @Test @SuppressWarnings("unchecked") void cancelledGenerationDoesNotStartToolsOrModel() {
        ((Map<String,Boolean>)ReflectionTestUtils.getField(handler,"stopFlags")).put("g",true);
        var future=new CompletableFuture<String>();run(future);assertEquals("",future.join());verifyNoInteractions(router);
        verify(tools,never()).executeTool(anyString(),anyMap(),anyString());
    }
    @Test @SuppressWarnings("unchecked") void cancellationWhileRetrievingIgnoresLateEvidenceAndStopsBeforeFinalModel() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new CountDownLatch(1);
        when(tools.executeTool(anyString(),anyMap(),eq("1"))).thenAnswer(i->{
            started.countDown();try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException cancelled) { interrupted.countDown(); }
            return result("search_knowledge","late","TEXT_CHUNK");
        });
        var handle=handle();
        when(router.streamReActTurn(eq("1"),anyList(),anyList(),anyInt(),any(),any(),any())).thenAnswer(i->{
            ((Consumer<LlmProviderRouter.ReActTurn>)i.getArgument(6)).accept(turn("",List.of(call("1","search_knowledge","slow"))));return handle;
        });
        var future=new CompletableFuture<String>();var loop=CompletableFuture.runAsync(()->run(future));
        try {
            assertTrue(started.await(3,TimeUnit.SECONDS));
            ((Map<String,Boolean>)ReflectionTestUtils.getField(handler,"stopFlags")).put("g",true);
            loop.get(3,TimeUnit.SECONDS);assertEquals("",future.get(1,TimeUnit.SECONDS));
            assertTrue(interrupted.await(1,TimeUnit.SECONDS));
            verify(router,times(1)).streamReActTurn(anyString(),anyList(),anyList(),anyInt(),any(),any(),any());
            verify(generations,never()).markCompleted(anyString(),anyMap(),anyString(),anyBoolean(),anyInt());
            assertFalse(((Map<?,?>)ReflectionTestUtils.getField(handler,"generationReferenceMappings")).containsKey("g"));
        } finally { release.countDown(); }
    }
}
