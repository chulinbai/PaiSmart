package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;

@Service
public class ChatRunFinalizer {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ChatRunFinalizer.class);
    private final AgentRunRepository runs;
    private final ConversationRepository conversations;
    private final ConversationSessionRepository sessions;
    private final UserRepository users;
    private final AgentContextService context;
    private final ChatGenerationStateService snapshots;
    private final ChatSessionRegistry sockets;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Map<String,Gate> gates=new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private io.micrometer.core.instrument.MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Autowired
    private AgentToolExecutionRepository toolExecutions;
    private final Instant toolStartup=Instant.now();
    private boolean toolAuditComplete;
    private final LocalDateTime startup=LocalDateTime.now();
    public ChatRunFinalizer(AgentRunRepository runs,ConversationRepository conversations,ConversationSessionRepository sessions,
            UserRepository users,AgentContextService context,ChatGenerationStateService snapshots,ChatSessionRegistry sockets,
            StringRedisTemplate redis,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.runs=runs;this.conversations=conversations;this.sessions=sessions;this.users=users;this.context=context;
        this.snapshots=snapshots;this.sockets=sockets;this.redis=redis;this.mapper=mapper;
        tx=new TransactionTemplate(manager);tx.setTimeout(5);tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public static final class Gate {
        public final long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        public long sequence;
        public volatile boolean frozen;
        public volatile boolean cancelled;
        private Intent intent;
        private Consumer<Intent> committed;
        private volatile boolean published;
        private volatile long publishedAt;
        private boolean processing;
        private boolean recovered;
        private int failures;
        private long retryAt;
        private long lastAlert;
        public boolean active(){return !frozen && !cancelled && System.nanoTime()<deadline;}
    }
    public record Intent(String generationId,String token,String userId,String conversationId,String question,String answer,
            Map<String,Map<String,Object>> references,AgentRun.RunStatus status,String finishReason,int promptTokens,int completionTokens,
            long lastSequence,long createdAt,String diagnostic) {}
    public Gate register(String generation){return gates.computeIfAbsent(generation,ignored->new Gate());}
    public Gate gate(String generation){return gates.get(generation);}
    public boolean pending(String generation){Gate gate=gates.get(generation);return gate!=null && gate.frozen && !gate.published;}
    public Optional<Intent> pendingIntent(String generation) {
        Gate gate=gates.get(generation);
        if(gate==null) return Optional.empty();
        synchronized(gate){return gate.frozen && !gate.published?Optional.ofNullable(gate.intent):Optional.empty();}
    }
    public void submit(String generation,Supplier<Intent> freeze,Consumer<Intent> committed) {
        Gate gate=Objects.requireNonNull(gates.get(generation),"Generation not registered");
        synchronized(gate) {
            if(gate.frozen) return;
            gate.intent=freeze.get();gate.committed=committed;gate.frozen=true;
        }
        publishSaving(gate);
        attempt(gate,false);
    }
    public static boolean terminal(AgentRun.RunStatus status) {
        return Set.of(AgentRun.RunStatus.COMPLETED,AgentRun.RunStatus.INCOMPLETE,AgentRun.RunStatus.REJECTED,AgentRun.RunStatus.FAILED,AgentRun.RunStatus.CANCELLED).contains(status);
    }
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private Intent read(String value){try{return mapper.readValue(value,Intent.class);}catch(Exception e){throw new IllegalStateException("Invalid finalization intent",e);}}
    private String recoveryKey(String generation){return "chat:finalization:"+generation;}
    private void persistIntent(Intent intent,boolean recovery) {
        String serialized=json(intent);
        tx.executeWithoutResult(s->{
            var run=runs.lock(intent.generationId()).orElseThrow();
            if(run.getFinalizationToken()!=null) {
                if(!run.getFinalizationToken().equals(intent.token()) || !Objects.equals(run.getFinalizationIntent(),serialized))
                    throw new IllegalStateException("Finalization intent conflict");
                return;
            }
            if(run.getStatus()!=AgentRun.RunStatus.CREATED && run.getStatus()!=AgentRun.RunStatus.RUNNING
                && !(recovery && run.getStatus()==AgentRun.RunStatus.RECOVERABLE)) throw new IllegalStateException("Run cannot accept terminal intent");
            run.setFinalizationToken(intent.token());run.setFinalizationIntent(serialized);run.setStatus(AgentRun.RunStatus.FINALIZING);
            run.setFinalizationStartedAt(LocalDateTime.ofInstant(Instant.ofEpochMilli(intent.createdAt()),ZoneId.systemDefault()));
            runs.saveAndFlush(run);
        });
    }
    private void commit(Intent intent) {
        tx.executeWithoutResult(s->{
            var run=runs.lock(intent.generationId()).orElseThrow();
            if(!intent.token().equals(run.getFinalizationToken())) throw new IllegalStateException("Finalization ownership lost");
            if(terminal(run.getStatus())) return;
            if(run.getStatus()!=AgentRun.RunStatus.FINALIZING) throw new IllegalStateException("Run not finalizing");
            var session=sessions.lock(intent.conversationId()).orElseThrow();
            if(conversations.findByGenerationId(intent.generationId()).isEmpty()) {
                Conversation answer=new Conversation();answer.setGenerationId(intent.generationId());
                answer.setUser(users.getReferenceById(Long.valueOf(intent.userId())));answer.setConversationId(intent.conversationId());
                answer.setQuestion(intent.question());answer.setAnswer(intent.answer());answer.setReferenceMappingsJson(json(intent.references()));
                answer.setAnswerStatus(intent.status().name());answer.setFinishReason(intent.finishReason());
                answer.setResponseMetadataJson(json(Map.of("token",intent.token(),"lastSequence",intent.lastSequence())));
                conversations.saveAndFlush(answer);
            }
            context.appendEventOnce(Long.valueOf(intent.userId()),intent.conversationId(),intent.generationId(),ContextEvent.EventType.MESSAGE,
                    "assistant",intent.answer(),Map.of("status",intent.status().name(),"finishReason",intent.finishReason(),"referenceMappings",intent.references()),"final:"+intent.generationId());
            run.setStatus(intent.status());run.setFinishReason(intent.finishReason());run.setPromptTokens(intent.promptTokens());run.setCompletionTokens(intent.completionTokens());
            run.setPersistenceError(null);run.setNextRecoveryAt(null);runs.saveAndFlush(run);
            if("新对话".equals(session.getTitle()) && intent.question()!=null && !intent.question().isBlank())
                session.setTitle(intent.question().substring(0,Math.min(50,intent.question().length())));
            session.setUpdatedAt(LocalDateTime.now());sessions.save(session);
        });
    }
    private void attempt(Gate gate,boolean recovery) {
        synchronized(gate){if(gate.processing || gate.published || gate.intent==null || System.currentTimeMillis()<gate.retryAt)return;gate.processing=true;}
        Intent intent=gate.intent;
        try {
            try {persistIntent(intent,recovery || gate.recovered);}
            catch(RuntimeException uncertain) {
                // Query first. A client exception does not prove rollback.
                boolean found=false;
                try {var run=runs.findById(intent.generationId()).orElse(null);found=run!=null && intent.token().equals(run.getFinalizationToken()) && json(intent).equals(run.getFinalizationIntent());}
                catch(RuntimeException ignored){}
                if(!found) {saveRecovery(intent);throw uncertain;}
            }
            try {commit(intent);}
            catch(RuntimeException uncertain) {
                var run=runs.findById(intent.generationId()).orElseThrow();
                if(!intent.token().equals(run.getFinalizationToken()) || !terminal(run.getStatus())) throw uncertain;
            }
            // Redis is a projection. Its failure cannot replace the committed MySQL outcome.
            try {snapshots.saveFinalSnapshot(intent);}catch(RuntimeException e){log.warn("Final snapshot projection pending: generation={}",intent.generationId());}
            synchronized(gate){
                if(!gate.published) {
                    sockets.sendJsonToGeneration(intent.generationId(),terminalPayload(intent));
                    gate.published=true;
                    gate.publishedAt=System.currentTimeMillis();
                    if(gate.committed!=null) gate.committed.accept(intent);
                }
            }
            try {redis.delete(recoveryKey(intent.generationId()));}catch(RuntimeException ignored){}
        } catch(RuntimeException error) {
            gate.failures++;
            int[] seconds={1,2,5,10,30};long delay=gate.failures<=seconds.length?seconds[gate.failures-1]:60;
            gate.retryAt=System.currentTimeMillis()+delay*1000;
            try {tx.executeWithoutResult(s->{var run=runs.lock(intent.generationId()).orElseThrow();
                if(terminal(run.getStatus()))return;
                run.setFinalizationAttempts(gate.failures);run.setPersistenceError(error.getClass().getSimpleName());
                run.setNextRecoveryAt(LocalDateTime.now().plusSeconds(delay));runs.save(run);});}catch(RuntimeException ignored){}
            publishSaving(gate);
        } finally {synchronized(gate){gate.processing=false;}}
    }
    private void saveRecovery(Intent intent) {
        String value=json(intent),key=recoveryKey(intent.generationId());
        try {
            if(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key,value))) return;
        }catch(RuntimeException ignored){}
        if(!value.equals(redis.opsForValue().get(key))) throw new IllegalStateException("Recovery record unavailable or conflicting");
    }
    private void publishSaving(Gate gate) {
        long age=System.currentTimeMillis()-gate.intent.createdAt();
        sockets.sendJsonToGeneration(gate.intent.generationId(),Map.of("type","persistence_status","generationId",gate.intent.generationId(),
            "conversationId",gate.intent.conversationId(),"persistenceStatus",age>=30000?"DELAYED":"PENDING",
            "message",age>=30000?"保存延迟，正在重试":"正在保存"));
        if(age>=300000 && System.currentTimeMillis()-gate.lastAlert>=900000){
            gate.lastAlert=System.currentTimeMillis();
            log.error("Finalization delayed over five minutes: generation={}",gate.intent.generationId());
            if(metrics!=null) metrics.counter("llm.finalization.delayed").increment();
        }
    }
    public Map<String,Object> terminalPayload(Intent i) {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("type","completion");payload.put("generationId",i.generationId());
        payload.put("conversationId",i.conversationId());payload.put("status",i.status().name());payload.put("token",i.token());payload.put("lastSequence",i.lastSequence());
        payload.put("content",i.answer());payload.put("referenceMappings",i.references());payload.put("finishReason",i.finishReason());
        payload.put("truncated",i.status()==AgentRun.RunStatus.INCOMPLETE);payload.put("hasError",i.status()==AgentRun.RunStatus.FAILED || i.status()==AgentRun.RunStatus.REJECTED);
        payload.put("persistenceStatus","SAVED");return payload;
    }
    @Scheduled(fixedDelay=1000,initialDelay=1000)
    public void recover() {
        if(!toolAuditComplete && toolExecutions!=null) {
            try {toolExecutions.markInterrupted(toolStartup);toolAuditComplete=true;}
            catch(RuntimeException e){log.warn("Tool execution startup audit pending");}
        }
        gates.entrySet().removeIf(e -> e.getValue().published
                && System.currentTimeMillis()-e.getValue().publishedAt>3600000);
        gates.values().stream().filter(g->g.frozen && !g.published).forEach(g->attempt(g,false));
        for(var run:runs.findByStatusIn(List.of(AgentRun.RunStatus.FINALIZING,AgentRun.RunStatus.CREATED,AgentRun.RunStatus.RUNNING))) {
            if(gates.containsKey(run.getGenerationId())) continue;
            try {
                String value=run.getFinalizationIntent();
                if(value==null) {
                    if(run.getCreatedAt()!=null && run.getCreatedAt().isAfter(startup)) continue;
                    value=redis.opsForValue().get(recoveryKey(run.getGenerationId()));
                }
                if(value!=null) {
                    Intent intent=read(value);Gate gate=new Gate();gate.frozen=true;gate.recovered=true;gate.intent=intent;
                    Gate existing=gates.putIfAbsent(run.getGenerationId(),gate);attempt(existing==null?gate:existing,true);
                } else {
                    tx.executeWithoutResult(s->{var locked=runs.lock(run.getGenerationId()).orElseThrow();
                        if(locked.getFinalizationToken()==null && (locked.getStatus()==AgentRun.RunStatus.CREATED || locked.getStatus()==AgentRun.RunStatus.RUNNING)) {
                            locked.setStatus(AgentRun.RunStatus.RECOVERABLE);locked.setPersistenceError("service restarted without confirmed terminal intent");runs.save(locked);
                        }});
                }
            }catch(RuntimeException e){log.warn("Terminal recovery pending: generation={}",run.getGenerationId());}
        }
    }
}
