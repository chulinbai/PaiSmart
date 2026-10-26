package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.UsageQuotaProperties;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yizhaoqi.smartpai.model.LlmRequestAttempt.*;

@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="LLM_TEST_MYSQL_URL",matches=".*paismart_llm_reliability_test.*")
@Import({LlmReliabilityIntegrationTest.Config.class,LlmUsageSettlementService.class,LlmAttemptService.class,AgentContextService.class,ChatRunFinalizer.class})
class LlmReliabilityIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("LLM_TEST_MYSQL_URL"));
        registry.add("spring.datasource.username",()->"root");registry.add("spring.datasource.password",()->"graph-test-only");
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto",()->"update");
        registry.add("spring.jpa.properties.hibernate.dialect",()->"org.hibernate.dialect.MySQLDialect");
        registry.add("spring.jpa.show-sql",()->false);
    }
    @TestConfiguration static class Config {
        @Bean UsageQuotaProperties quota(){return new UsageQuotaProperties();}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean LettuceConnectionFactory redisConnectionFactory(){
            var config=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getenv("LLM_TEST_REDIS_PORT")));config.setDatabase(8);
            return new LettuceConnectionFactory(config,LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
        }
        @Bean StringRedisTemplate redis(LettuceConnectionFactory factory){return new StringRedisTemplate(factory);}
    }
    @MockBean RateLimitConfigService limits;
    @MockBean UserTokenService balances;
    @MockBean ContextArtifactService artifacts;
    @MockBean TokenCounter counter;
    @MockBean ModelProviderConfigService providers;
    @MockBean LlmProviderRouter router;
    @MockBean ChatGenerationStateService snapshots;
    @MockBean ChatSessionRegistry sockets;
    @Autowired UsageQuotaProperties quota;
    @Autowired LlmUsageSettlementService settlement;
    @Autowired LlmAttemptService service;
    @Autowired LlmRequestAttemptRepository attempts;
    @Autowired AgentRunRepository runs;
    @Autowired UserRepository users;
    @Autowired UserTokenRecordRepository records;
    @Autowired AgentWorkspaceRepository workspaces;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationSessionRepository sessions;
    @Autowired ContextEventRepository events;
    @Autowired AgentToolExecutionRepository tools;
    @Autowired AgentContextService context;
    @Autowired ChatRunFinalizer finalizer;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    User user;
    String userId,conversation,generation,workspace;
    final ModelProviderConfigService.ActiveProviderView provider=new ModelProviderConfigService.ActiveProviderView("fixture","fixture","openai-compatible","http://127.0.0.1:1","fixture","",null,16000);

    @BeforeEach void setup() {
        quota.setUseUserTokenBalance(false);quota.getLlm().setEnabled(true);quota.getLlm().setDayMaxTokens(1000);
        when(limits.getCurrentSettings()).thenReturn(new RateLimitConfigService.RateLimitSettingsView(null,new RateLimitConfigService.TokenBudgetView(0,60,0,86400),null,null,null));
        user=new User();user.setUsername("llm-test-"+UUID.randomUUID());user.setPassword("test-only");user.setRole(User.Role.USER);user=users.saveAndFlush(user);userId=user.getId().toString();
        workspace=UUID.randomUUID().toString();var w=new AgentWorkspace();w.setId(workspace);w.setOwnerUserId(user.getId());w.setName("fixture");w.setType(AgentWorkspace.WorkspaceType.KNOWLEDGE);workspaces.saveAndFlush(w);
        conversation=UUID.randomUUID().toString();var s=new ConversationSession();s.setConversationId(conversation);s.setUser(user);s.setWorkspaceId(workspace);s.setTitle("fixture");sessions.saveAndFlush(s);
        generation=UUID.randomUUID().toString();var run=new AgentRun();run.setGenerationId(generation);run.setThreadId(conversation);run.setUserId(user.getId());run.setWorkspaceId(workspace);run.setStatus(AgentRun.RunStatus.RUNNING);runs.saveAndFlush(run);
        context.configureToolJournal(tools,manager);
    }
    LlmCallContext call(boolean extra) {return new LlmCallContext(generation,null,UUID.randomUUID().toString(),"fixture",extra,System.nanoTime()+TimeUnit.MINUTES.toNanos(2),new Object(),()->true,provider);}
    String prepare(int prompt,int maximum){return service.prepare(call(false),userId,prompt,maximum);}
    String sent(int prompt,int maximum){var c=call(false);String id=service.prepare(c,userId,prompt,maximum);service.admit(id,c);return id;}
    String dailyKey(){return "quota:llm:"+LocalDate.now()+":user:"+userId;}
    String balanceKey(){return "user:token:llm:"+userId;}
    void parallel(Runnable first,Runnable second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try {var a=pool.submit(()->{start.await();first.run();return null;});var b=pool.submit(()->{start.await();second.run();return null;});start.countDown();a.get(15,TimeUnit.SECONDS);b.get(15,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();}
    }
    @Test void concurrentReservationsCannotBothSpendOneQuota() throws Exception {
        AtomicInteger accepted=new AtomicInteger(),rejected=new AtomicInteger();
        Runnable reserve=()->{try{prepare(100,700);accepted.incrementAndGet();}catch(com.yizhaoqi.smartpai.exception.RateLimitExceededException e){rejected.incrementAndGet();}};
        parallel(reserve,reserve);assertEquals(1,accepted.get());assertEquals(1,rejected.get());assertEquals("800",redis.opsForValue().get(dailyKey()));
    }
    @Test void overageAndConcurrentSettlementChargeExactlyOnce() throws Exception {
        String id=sent(100,700);settlement.freeze(id,100,1000,1000,true,null);
        parallel(()->settlement.settle(id),()->settlement.settle(id));
        assertEquals("1100",redis.opsForValue().get(dailyKey()));assertEquals(SettlementStatus.SETTLED,attempts.findById(id).orElseThrow().getSettlementStatus());
        var record=records.findByUserIdAndRecordDateAndTokenTypeAndChangeType(userId,LocalDate.now(),UserTokenRecord.TokenType.LLM,UserTokenRecord.ChangeType.CONSUME).orElseThrow();
        assertEquals(1100,record.getAmount());assertEquals(1,record.getRequestCount());assertThrows(com.yizhaoqi.smartpai.exception.RateLimitExceededException.class,()->prepare(1,1));
    }
    @Test void balanceModeRetainsConcurrentNegativeBalancePolicy() throws Exception {
        quota.setUseUserTokenBalance(true);redis.opsForValue().set(balanceKey(),"1000");
        String a=sent(100,700),b=sent(100,700);settlement.freeze(a,100,700,0,true,null);settlement.freeze(b,100,700,0,true,null);
        parallel(()->settlement.settle(a),()->settlement.settle(b));settlement.settle(a);settlement.settle(b);
        assertEquals("-600",redis.opsForValue().get(balanceKey()));assertThrows(com.yizhaoqi.smartpai.exception.RateLimitExceededException.class,()->prepare(1,1));
    }
    @Test void redisAppliedMysqlFailedCanBeConfirmedWithoutSecondDebit() {
        quota.setUseUserTokenBalance(true);redis.opsForValue().set(balanceKey(),"1000");String id=sent(100,700);settlement.freeze(id,100,100,0,true,null);
        var failingRecords=mock(UserTokenRecordRepository.class);when(failingRecords.findByUserIdAndRecordDateAndTokenTypeAndChangeType(any(),any(),any(),any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("injected database failure"));
        var failing=new LlmUsageSettlementService(attempts,redis,quota,limits,balances,failingRecords,users,mapper,manager,environment);
        assertThrows(RuntimeException.class,()->failing.settle(id));assertEquals("800",redis.opsForValue().get(balanceKey()));
        assertEquals(SettlementStatus.APPLYING,attempts.findById(id).orElseThrow().getSettlementStatus());
        settlement.settle(id);assertEquals("800",redis.opsForValue().get(balanceKey()));assertEquals(SettlementStatus.SETTLED,attempts.findById(id).orElseThrow().getSettlementStatus());
    }
    @Test void absentReceiptDoesNotMeanNeverCharged() {
        String id=sent(100,700);settlement.freeze(id,100,100,0,true,null);redis.delete(settlement.receiptKey(id));settlement.settle(id);
        assertEquals(SettlementStatus.RECONCILE_REQUIRED,attempts.findById(id).orElseThrow().getSettlementStatus());assertEquals("800",redis.opsForValue().get(dailyKey()));
    }
    @Test void frozenVersionCannotBeReplacedByLateUsageOrConflictingReceipt() throws Exception {
        String id=sent(100,700);settlement.freeze(id,100,100,0,true,null);settlement.freeze(id,900,900,0,true,null);settlement.settle(id);
        assertEquals(200,attempts.findById(id).orElseThrow().getFrozenTokens());
        assertEquals(900,attempts.findById(id).orElseThrow().getLateCompletionTokens());
        var receipt=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(redis.opsForValue().get(settlement.receiptKey(id)));receipt.put("fingerprint","stale-worker");redis.opsForValue().set(settlement.receiptKey(id),receipt.toString());settlement.settle(id);
        assertEquals(SettlementStatus.SETTLED,attempts.findById(id).orElseThrow().getSettlementStatus());assertEquals("200",redis.opsForValue().get(dailyKey()));
    }
    @Test void oldWindowNeverAdjustsNewWindow() {
        quota.setUseUserTokenBalance(true);redis.opsForValue().set(balanceKey(),"5000");
        when(limits.getCurrentSettings()).thenReturn(new RateLimitConfigService.RateLimitSettingsView(null,new RateLimitConfigService.TokenBudgetView(1000,60,0,86400),null,null,null));
        String key="budget:llm:global:minute";redis.delete(List.of(key,key+":epoch"));String a=sent(100,700);
        redis.delete(List.of(key,key+":epoch"));String b=sent(100,700);settlement.freeze(a,100,1000,0,true,null);settlement.settle(a);
        assertEquals("800",redis.opsForValue().get(key));assertEquals(Phase.SENDING,attempts.findById(b).orElseThrow().getPhase());
        redis.delete(List.of(key,key+":epoch"));
    }
    @Test void preparedCancellationReleasesReservationButSendingRecoveryUsesObservedEstimate() {
        String prepared=prepare(100,700);service.finish(prepared,null,null,0,false,"cancelled before send");assertEquals("0",redis.opsForValue().get(dailyKey()));
        String sending=sent(100,700);settlement.freeze(sending,null,null,0,false,"restart outcome unknown");settlement.settle(sending);
        var recovered=attempts.findById(sending).orElseThrow();assertEquals(100,recovered.getFrozenTokens());assertEquals("ESTIMATED",recovered.getUsageSource());assertEquals("100",redis.opsForValue().get(dailyKey()));
    }
    @Test void concurrentExtraAdmissionsAreLimitedToTwoAndNeverRefunded() throws Exception {
        quota.getLlm().setDayMaxTokens(10000);var a=call(true);var b=call(true);String idA=service.prepare(a,userId,10,10),idB=service.prepare(b,userId,10,10);
        parallel(()->service.admit(idA,a),()->service.admit(idB,b));
        var c=call(true);String idC=service.prepare(c,userId,10,10);assertThrows(IllegalStateException.class,()->service.admit(idC,c));
        assertEquals(2,runs.findById(generation).orElseThrow().getRecoveryAttempts());service.finish(idA,null,null,0,false,"interrupted");
        assertEquals(2,runs.findById(generation).orElseThrow().getRecoveryAttempts());
    }
    ChatRunFinalizer.Intent intent(AgentRun.RunStatus status) {
        return new ChatRunFinalizer.Intent(generation,UUID.randomUUID().toString(),userId,conversation,"question","partial (来源#1: fixture)",
                Map.of("1",Map.of("fileMd5","fixture-md5")),status,"fixture",1,2,5,System.currentTimeMillis(),null);
    }
    ChatRunFinalizer worker(ConversationRepository repository,PlatformTransactionManager transactionManager) {
        return new ChatRunFinalizer(runs,repository,sessions,users,context,snapshots,sockets,redis,mapper,transactionManager);
    }
    @Test void completionCancellationRaceCommitsOneFrozenAnswer() throws Exception {
        var session=sessions.findByConversationId(conversation).orElseThrow();session.setTitle("新对话");sessions.saveAndFlush(session);
        finalizer.register(generation);AtomicInteger published=new AtomicInteger();
        parallel(()->finalizer.submit(generation,()->intent(AgentRun.RunStatus.COMPLETED),i->published.incrementAndGet()),
            ()->finalizer.submit(generation,()->intent(AgentRun.RunStatus.CANCELLED),i->published.incrementAndGet()));
        assertEquals(1,published.get());assertTrue(conversations.findByGenerationId(generation).isPresent());
        assertEquals(1,events.findByThreadIdOrderBySequenceAsc(conversation).size());assertTrue(ChatRunFinalizer.terminal(runs.findById(generation).orElseThrow().getStatus()));
        assertEquals("question",sessions.findByConversationId(conversation).orElseThrow().getTitle());
    }
    @Test void twoWorkersRecoverIntentAfterAnswerCommitFailureWithoutDuplicates() throws Exception {
        var broken=mock(ConversationRepository.class);when(broken.findByGenerationId(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("injected"));
        var original=worker(broken,manager);original.register(generation);var expected=intent(AgentRun.RunStatus.INCOMPLETE);original.submit(generation,()->expected,i->fail());
        assertEquals(AgentRun.RunStatus.FINALIZING,runs.findById(generation).orElseThrow().getStatus());
        var first=worker(conversations,manager);var second=worker(conversations,manager);parallel(first::recover,second::recover);
        var answer=conversations.findByGenerationId(generation).orElseThrow();assertEquals(expected.answer(),answer.getAnswer());assertTrue(answer.getReferenceMappingsJson().contains("fixture-md5"));
        assertEquals(AgentRun.RunStatus.INCOMPLETE,runs.findById(generation).orElseThrow().getStatus());assertEquals(1,events.findByThreadIdOrderBySequenceAsc(conversation).size());
    }
    @Test void databaseCommitThenClientExceptionIsResolvedByTokenLookup() {
        AtomicBoolean failOnce=new AtomicBoolean(true);
        PlatformTransactionManager ambiguous=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void rollback(TransactionStatus s){manager.rollback(s);}
            public void commit(TransactionStatus s){manager.commit(s);if(failOnce.getAndSet(false))throw new org.springframework.dao.DataAccessResourceFailureException("commit acknowledged late");}
        };
        var w=worker(conversations,ambiguous);w.register(generation);AtomicInteger completed=new AtomicInteger();w.submit(generation,()->intent(AgentRun.RunStatus.COMPLETED),i->completed.incrementAndGet());
        assertEquals(1,completed.get());assertEquals(AgentRun.RunStatus.COMPLETED,runs.findById(generation).orElseThrow().getStatus());
    }
    @Test void durableToolResultIsReusedAndIdentityIncludesLogicalCall() {
        var call=new LlmProviderRouter.ToolCallDecision("same","submit_feedback",Map.of("rating","good"));AtomicInteger invoked=new AtomicInteger();
        java.util.function.Supplier<AgentToolRegistry.ToolExecutionResult> action=()->{invoked.incrementAndGet();return new AgentToolRegistry.ToolExecutionResult(call.name(),true,"saved",Map.of());};
        context.executeRecordedTool(user.getId(),conversation,generation,"logical-a",call,action);
        context.executeRecordedTool(user.getId(),conversation,generation,"logical-a",call,action);
        context.executeRecordedTool(user.getId(),conversation,generation,"logical-b",call,action);assertEquals(2,invoked.get());
        assertThrows(IllegalStateException.class,()->context.executeRecordedTool(user.getId(),conversation,generation,"logical-a",new LlmProviderRouter.ToolCallDecision("same","submit_feedback",Map.of("rating","bad")),action));
    }
    @Test void feedbackAppliedButUncertainResultIsNeverAutomaticallyReplayed() {
        var call=new LlmProviderRouter.ToolCallDecision("feedback","submit_feedback",Map.of("rating","good"));AtomicInteger invoked=new AtomicInteger();
        java.util.function.Supplier<AgentToolRegistry.ToolExecutionResult> action=()->{
            invoked.incrementAndGet();redis.opsForValue().set("test:feedback:"+generation,"saved");throw new IllegalStateException("simulated timeout after side effect");};
        assertThrows(IllegalStateException.class,()->context.executeRecordedTool(user.getId(),conversation,generation,"logical",call,action));
        assertThrows(IllegalStateException.class,()->context.executeRecordedTool(user.getId(),conversation,generation,"logical",call,action));
        assertEquals(1,invoked.get());assertEquals("saved",redis.opsForValue().get("test:feedback:"+generation));
    }
    @Test void duplicateReservationWorkersReserveSameAttemptOnce() throws Exception {
        var a=new LlmRequestAttempt();a.setId(UUID.randomUUID().toString());a.setLogicalCallId("reservation");a.setUserId(userId);a.setEstimatedPromptTokens(100);
        attempts.saveAndFlush(a);
        parallel(()->settlement.reserve(a.getId(),700),()->settlement.reserve(a.getId(),700));
        assertEquals("800",redis.opsForValue().get(dailyKey()));
        assertEquals(ReservationStatus.RESERVED,attempts.findById(a.getId()).orElseThrow().getReservationStatus());
        assertThrows(IllegalStateException.class,()->settlement.reserve(a.getId(),800));
        assertEquals("800",redis.opsForValue().get(dailyKey()));
        assertEquals(SettlementStatus.RECONCILE_REQUIRED,attempts.findById(a.getId()).orElseThrow().getSettlementStatus());
    }
    @Test void rejectedGlobalComponentDoesNotLeavePartialUserReservation() {
        when(limits.getCurrentSettings()).thenReturn(new RateLimitConfigService.RateLimitSettingsView(null,new RateLimitConfigService.TokenBudgetView(1000,60,0,86400),null,null,null));
        String key="budget:llm:global:minute";
        redis.opsForValue().set(key,"900",Duration.ofSeconds(60));redis.opsForValue().set(key+":epoch","fixture",Duration.ofSeconds(60));
        try {
            assertThrows(com.yizhaoqi.smartpai.exception.RateLimitExceededException.class,()->prepare(100,700));
            assertTrue(redis.opsForValue().get(dailyKey())==null || "0".equals(redis.opsForValue().get(dailyKey())));
            assertEquals("900",redis.opsForValue().get(key));
        } finally {redis.delete(List.of(key,key+":epoch"));}
    }
    @Test void successfulSideEffectWithResultRollbackCannotBeExecutedAgain() {
        AtomicInteger commits=new AtomicInteger(),invoked=new AtomicInteger();
        PlatformTransactionManager failing=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void rollback(TransactionStatus s){manager.rollback(s);}
            public void commit(TransactionStatus s){
                if(s.isNewTransaction() && commits.incrementAndGet()==3){manager.rollback(s);throw new org.springframework.dao.DataAccessResourceFailureException("result write failed");}
                manager.commit(s);
            }
        };
        context.configureToolJournal(tools,failing);
        var call=new LlmProviderRouter.ToolCallDecision("persist-failed","submit_feedback",Map.of("rating","good"));
        java.util.function.Supplier<AgentToolRegistry.ToolExecutionResult> action=()->{invoked.incrementAndGet();return new AgentToolRegistry.ToolExecutionResult(call.name(),true,"saved",Map.of());};
        try {
            assertThrows(RuntimeException.class,()->context.executeRecordedTool(user.getId(),conversation,generation,"logical",call,action));
        } finally {context.configureToolJournal(tools,manager);}
        tools.markInterrupted(Instant.now().plusSeconds(1));
        tools.markInterrupted(Instant.now().plusSeconds(1));
        var record=tools.findById(LlmUsageSettlementService.fingerprint(generation+"|logical|persist-failed")).orElseThrow();
        assertEquals(AgentToolExecution.Status.EXECUTING,record.getStatus());assertTrue(record.getDiagnostic().startsWith("OUTCOME_UNKNOWN"));
        assertThrows(IllegalStateException.class,()->context.executeRecordedTool(user.getId(),conversation,generation,"logical",call,action));
        assertEquals(1,invoked.get());
    }
    @Test void invalidLaterRedisCounterCannotLeavePartialSettlement() {
        when(limits.getCurrentSettings()).thenReturn(new RateLimitConfigService.RateLimitSettingsView(null,new RateLimitConfigService.TokenBudgetView(1000,60,0,86400),null,null,null));
        String key="budget:llm:global:minute";redis.delete(List.of(key,key+":epoch"));
        try {
            String id=sent(100,700);settlement.freeze(id,100,100,0,true,null);
            redis.opsForValue().set(key,"0.5");settlement.settle(id);
            assertEquals("800",redis.opsForValue().get(dailyKey()));assertEquals("0.5",redis.opsForValue().get(key));
            assertEquals(SettlementStatus.RECONCILE_REQUIRED,attempts.findById(id).orElseThrow().getSettlementStatus());
        } finally {redis.delete(List.of(key,key+":epoch"));}
    }
    @Test void auditOfMissingAppliedReceiptKeepsSettledFactAndNeverChargesAgain() {
        String id=sent(100,700);settlement.freeze(id,100,100,0,true,null);settlement.settle(id);redis.delete(settlement.receiptKey(id));
        var scoped=mock(LlmRequestAttemptRepository.class);
        when(scoped.findTop100BySettlementStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(anyList(),any())).thenReturn(List.of(attempts.findById(id).orElseThrow()));
        new LlmAttemptService(scoped,runs,settlement,manager).auditSettledReceipts();
        var row=attempts.findById(id).orElseThrow();assertEquals(SettlementStatus.SETTLED,row.getSettlementStatus());
        assertTrue(row.getDiagnostic().contains("missing receipt"));assertEquals("200",redis.opsForValue().get(dailyKey()));
    }
    @Test void redisFallbackRetainsWholeIntentWithoutTtlAndRecoversOriginalToken() {
        PlatformTransactionManager unavailable=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable");}
            public void rollback(TransactionStatus s){fail();}
            public void commit(TransactionStatus s){fail();}
        };
        var original=worker(conversations,unavailable);original.register(generation);var frozen=intent(AgentRun.RunStatus.INCOMPLETE);
        original.submit(generation,()->frozen,i->fail("Must not publish before SQL commit"));
        assertTrue(original.pending(generation));assertEquals(AgentRun.RunStatus.RUNNING,runs.findById(generation).orElseThrow().getStatus());
        assertEquals(-1L,redis.getExpire("chat:finalization:"+generation));
        assertTrue(redis.opsForValue().get("chat:finalization:"+generation).contains(frozen.token()));
        worker(conversations,manager).recover();
        assertEquals(frozen.token(),runs.findById(generation).orElseThrow().getFinalizationToken());
        assertEquals(frozen.answer(),conversations.findByGenerationId(generation).orElseThrow().getAnswer());
        assertNull(redis.opsForValue().get("chat:finalization:"+generation));
    }
}
