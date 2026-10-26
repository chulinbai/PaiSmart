package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LlmProviderRouterHttpTest {
    HttpServer server;ExecutorService executor;LlmProviderRouter router;LlmAttemptService attempts;
    ModelProviderConfigService.ActiveProviderView provider;
    final AtomicInteger requests=new AtomicInteger();final AtomicBoolean admitted=new AtomicBoolean();
    volatile String response="data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
    volatile long delay;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newCachedThreadPool();server.setExecutor(executor);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();assertTrue(admitted.get());exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            try {if(delay>0)Thread.sleep(delay);exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
            catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();
        provider=new ModelProviderConfigService.ActiveProviderView("fixture","fixture","openai-compatible","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","fixture","",null,16000);
        var quotas=mock(UsageQuotaService.class);when(quotas.estimateTextTokens(anyString())).thenAnswer(i->((String)i.getArgument(0)).length());
        router=new LlmProviderRouter(new AiProperties(),mock(RateLimitService.class),quotas,mock(ModelProviderConfigService.class),new ObjectMapper());
        attempts=mock(LlmAttemptService.class);when(attempts.prepare(any(),anyString(),anyInt(),anyInt())).thenReturn("attempt");
        doAnswer(i->{assertEquals(0,requests.get());admitted.set(true);return null;}).when(attempts).admit(anyString(),any());
        ReflectionTestUtils.setField(router,"attemptService",attempts);
    }
    @AfterEach void close(){server.stop(0);executor.shutdownNow();}
    LlmProviderRouter.StreamHandle start(CompletableFuture<LlmProviderRouter.ReActTurn> result,StringBuilder visible,LlmCallContext call){
        return router.streamReActTurn("1",List.of(Map.of("role","user","content","hi")),List.of(),100,
                visible::append,result::completeExceptionally,result::complete,call);
    }
    @Test void validStreamRequiresAdmissionBeforeHttpAndSettlesOnce() throws Exception {
        var result=new CompletableFuture<LlmProviderRouter.ReActTurn>();var handle=start(result,new StringBuilder(),LlmCallContext.background(null,"test",provider));
        assertEquals("answer",result.get(5,TimeUnit.SECONDS).content());handle.cancel();
        assertEquals(1,requests.get());verify(attempts,times(1)).finish(eq("attempt"),isNull(),isNull(),anyInt(),eq(true),isNull(),isNull(),isNull());
    }
    @Test void stopWithoutDoneRetainsVisibleTextButDoesNotCompleteSuccessfully() throws Exception {
        response=response.substring(0,response.indexOf("data: [DONE]"));var result=new CompletableFuture<LlmProviderRouter.ReActTurn>();var visible=new StringBuilder();
        start(result,visible,LlmCallContext.background(null,"test",provider));assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));
        assertEquals("answer",visible.toString());verify(attempts).finish(eq("attempt"),isNull(),isNull(),anyInt(),eq(false),anyString(),isNull(),isNull());
    }
    @Test void preparationFailureNeverSendsHttp() {
        when(attempts.prepare(any(),anyString(),anyInt(),anyInt())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class,()->start(new CompletableFuture<>(),new StringBuilder(),LlmCallContext.background(null,"test",provider)));
        assertEquals(0,requests.get());verify(attempts,never()).admit(anyString(),any());
    }
    @Test void absoluteDeadlineCancelsEvenBeforeAnyOutput() throws Exception {
        delay=2000;var result=new CompletableFuture<LlmProviderRouter.ReActTurn>();
        var call=new LlmCallContext(null,null,"deadline","test",false,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(400),new Object(),()->true,provider);
        long begin=System.nanoTime();start(result,new StringBuilder(),call);assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));
        assertTrue(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(2));assertEquals(1,requests.get());
    }
    @Test void cancellationBetweenAdmissionAndSubscribeNeverSendsHttp() {
        AtomicBoolean active=new AtomicBoolean(true);
        doAnswer(i->{admitted.set(true);active.set(false);return null;}).when(attempts).admit(anyString(),any());
        var call=new LlmCallContext(null,null,"cancel","test",false,System.nanoTime()+TimeUnit.SECONDS.toNanos(5),new Object(),active::get,provider);
        assertThrows(LlmResponseValidator.Failure.class,()->start(new CompletableFuture<>(),new StringBuilder(),call));
        assertEquals(0,requests.get());
    }
    @Test void fastStreamRetainsProviderRequestIdWithoutWaitingForCheckpoint() throws Exception {
        response="data: {\"id\":\"request-123\",\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        var result=new CompletableFuture<LlmProviderRouter.ReActTurn>();start(result,new StringBuilder(),LlmCallContext.background(null,"test",provider));
        result.get(5,TimeUnit.SECONDS);
        verify(attempts).finish(eq("attempt"),isNull(),isNull(),anyInt(),eq(true),isNull(),eq("request-123"),isNull());
    }
}
