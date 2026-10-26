package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.yizhaoqi.smartpai.model.LlmRequestAttempt.*;

@Service
public class LlmAttemptService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LlmAttemptService.class);
    private final LlmRequestAttemptRepository attempts;
    private final AgentRunRepository runs;
    private final LlmUsageSettlementService settlement;
    private final TransactionTemplate tx;
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private final Map<String, Completion> pending=new ConcurrentHashMap<>();
    private final Instant startup=Instant.now();
    public LlmAttemptService(LlmRequestAttemptRepository attempts,AgentRunRepository runs,LlmUsageSettlementService settlement,PlatformTransactionManager manager) {
        this.attempts=attempts;this.runs=runs;this.settlement=settlement;this.tx=new TransactionTemplate(manager);tx.setTimeout(5);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public String prepare(LlmCallContext call,String user,int estimatedPrompt,int maxOutput) {
        if(!call.valid()) throw new LlmResponseValidator.Failure(LlmResponseValidator.Kind.CANCELLED,"Call expired or cancelled");
        var a=new LlmRequestAttempt(); a.setId(UUID.randomUUID().toString());a.setGenerationId(call.generationId());a.setJobId(call.jobId());
        a.setLogicalCallId(call.logicalCallId());a.setUserId(user);a.setPurpose(call.purpose());a.setExtraRequest(call.extra());
        a.setProvider(call.provider().provider());a.setModel(call.provider().model());a.setEstimatedPromptTokens(estimatedPrompt);
        try {
            var endpoint=java.net.URI.create(call.provider().apiBaseUrl());
            String safeEndpoint=new java.net.URI(endpoint.getScheme(),null,endpoint.getHost(),endpoint.getPort(),endpoint.getPath(),null,null).toString();
            a.setProviderSnapshotJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "provider",String.valueOf(call.provider().provider()),"model",String.valueOf(call.provider().model()),
                "apiStyle",String.valueOf(call.provider().apiStyle()),"endpoint",safeEndpoint)));
        } catch(Exception e) {throw new LlmResponseValidator.Failure(LlmResponseValidator.Kind.CONFIG,"Invalid provider snapshot");}
        tx.executeWithoutResult(s -> attempts.saveAndFlush(a)); active.add(a.getId());
        try { settlement.reserve(a.getId(),maxOutput); return a.getId(); }
        catch(RuntimeException e) { finish(a.getId(),null,null,0,false,"quota admission failed"); throw e; }
    }
    /** Local admission, deliberately not an atomic transaction with provider HTTP. */
    public void admit(String id,LlmCallContext call) {
        synchronized(call.gate()) {
            if(!call.valid()) throw new LlmResponseValidator.Failure(LlmResponseValidator.Kind.CANCELLED,"Call expired or cancelled");
            tx.executeWithoutResult(s -> {
                AgentRun run=call.generationId()==null?null:runs.lock(call.generationId()).orElseThrow();
                if(run!=null) {
                    if(run.getStatus()!=AgentRun.RunStatus.RUNNING || run.isCancelRequested()) throw new IllegalStateException("Generation no longer active");
                    if(call.extra()) {
                        if(run.getRecoveryAttempts()>=2) throw new IllegalStateException("Recovery budget exhausted");
                        run.setRecoveryAttempts(run.getRecoveryAttempts()+1); runs.save(run);
                    }
                }
                var a=attempts.lock(id).orElseThrow();
                if(a.getPhase()!=Phase.PREPARED || a.getReservationStatus()!=ReservationStatus.RESERVED
                    || a.getSettlementStatus()!=SettlementStatus.PENDING) throw new IllegalStateException("Attempt not eligible for sending");
                a.setPhase(Phase.SENDING);a.setSent(true);a.setUpdatedAt(Instant.now());attempts.save(a);
            });
        }
    }
    public void progress(String id,Integer prompt,Integer completion,int observed,String requestId) {
        tx.executeWithoutResult(s -> {
            var a=attempts.lock(id).orElseThrow();
            if(a.getFrozenTokens()!=null || a.getPhase()==Phase.INTERRUPTED) return;
            a.setPhase(Phase.RECEIVING);a.setPromptTokens(prompt);a.setCompletionTokens(completion);
            a.setObservedOutputTokens(observed);a.setProviderRequestId(requestId);a.setUpdatedAt(Instant.now());attempts.save(a);
        });
    }
    public void finish(String id,Integer prompt,Integer completion,int observed,boolean finished,String diagnostic) {
        finish(id,prompt,completion,observed,finished,diagnostic,null);
    }
    public void finish(String id,Integer prompt,Integer completion,int observed,boolean finished,String diagnostic,String requestId) {
        finish(id,prompt,completion,observed,finished,diagnostic,requestId,null);
    }
    public void finish(String id,Integer prompt,Integer completion,int observed,boolean finished,String diagnostic,String requestId,String rawUsage) {
        pending.putIfAbsent(id,new Completion(prompt,completion,observed,finished,diagnostic,requestId,rawUsage));
        flush(id);
    }
    private void flush(String id) {
        Completion c=pending.get(id);
        if(c==null) return;
        try {
            settlement.freeze(id,c.prompt,c.completion,c.observed,c.finished,c.diagnostic,c.requestId,c.rawUsage);
            pending.remove(id,c);active.remove(id);
            settlement.settle(id);
        } catch(RuntimeException e) {log.warn("LLM attempt persistence pending: attempt={}, error={}",id,e.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=10000, initialDelay=10000)
    public void recover() {
        pending.keySet().forEach(this::flush);
        for(var a:attempts.findTop100BySettlementStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                List.of(SettlementStatus.PENDING,SettlementStatus.APPLYING),Instant.now().minusSeconds(10))) {
            if(active.contains(a.getId())) continue;
            try {
                if(a.getFrozenTokens()==null) {
                    if(a.getCreatedAt().isAfter(startup) && a.getUpdatedAt().isAfter(Instant.now().minusSeconds(180))) continue;
                    // NEW proves reserve was never invoked. REQUESTED is ambiguous and must be inspected.
                    if(a.getReservationStatus()==ReservationStatus.REQUESTED) {
                        try {settlement.reserve(a.getId(),a.getRequestedCompletionTokens());}catch(RuntimeException ignored){}
                    }
                    settlement.freeze(a.getId(),a.getPromptTokens(),a.getCompletionTokens(),a.getObservedOutputTokens(),false,"service restarted; transport outcome unknown");
                }
                settlement.settle(a.getId());
            } catch(RuntimeException e) {log.warn("Attempt recovery pending: attempt={}",a.getId());}
        }
    }
    /** Verify durable receipts without ever applying a debit to an already-settled attempt. */
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void auditSettledReceipts() {
        for(var a:attempts.findTop100BySettlementStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                List.of(SettlementStatus.SETTLED),Instant.now().minusSeconds(300))) {
            try {settlement.settle(a.getId());}
            catch(RuntimeException e){log.warn("Settlement receipt audit unavailable: attempt={}",a.getId());}
        }
    }
    private record Completion(Integer prompt,Integer completion,int observed,boolean finished,String diagnostic,String requestId,String rawUsage) {}
}
