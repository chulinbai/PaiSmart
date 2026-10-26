package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.UsageQuotaProperties;
import com.yizhaoqi.smartpai.model.*;
import com.yizhaoqi.smartpai.repository.*;
import com.yizhaoqi.smartpai.exception.RateLimitExceededException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.yizhaoqi.smartpai.model.LlmRequestAttempt.*;

@Service
public class LlmUsageSettlementService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlmUsageSettlementService.class);
    private final LlmRequestAttemptRepository attempts;
    private final StringRedisTemplate redis;
    private final UsageQuotaProperties properties;
    private final RateLimitConfigService limits;
    private final UserTokenService balances;
    private final UserTokenRecordRepository records;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<String> reserveScript = script("llm/reserve.lua");
    private final DefaultRedisScript<String> settleScript = script("llm/settle.lua");
    private final Object[] reservationLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private io.micrometer.core.instrument.MeterRegistry metrics;

    public LlmUsageSettlementService(LlmRequestAttemptRepository attempts, StringRedisTemplate redis,
            UsageQuotaProperties properties, RateLimitConfigService limits, UserTokenService balances,
            UserTokenRecordRepository records, UserRepository users, ObjectMapper mapper, PlatformTransactionManager manager,
            org.springframework.core.env.Environment environment) {
        this.attempts=attempts; this.redis=redis; this.properties=properties; this.limits=limits; this.balances=balances;
        this.records=records; this.users=users; this.mapper=mapper; this.tx=new TransactionTemplate(manager); tx.setTimeout(5);
        if (environment.containsProperty("spring.data.redis.cluster.nodes")) throw new IllegalStateException("LLM settlement requires standalone Redis; cross-slot fallback is forbidden");
    }
    private static DefaultRedisScript<String> script(String name) {
        var script = new DefaultRedisScript<String>(); script.setLocation(new ClassPathResource(name)); script.setResultType(String.class); return script;
    }
    public record Component(String mode, String key, String epochKey, String window, long ttlMillis, long limit, long reserved) {}
    public static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException(e); } }
    public String receiptKey(String id) { return "llm:attempt:"+id; }
    private List<Component> components(LlmRequestAttempt attempt, int maxOutput) {
        String user=attempt.getUserId();
        if (!properties.getLlm().isEnabled() || user==null || user.isBlank() || user.startsWith("system")) return List.of();
        long reserve=Math.max(1L, (long)attempt.getEstimatedPromptTokens()+maxOutput);
        List<Component> result=new ArrayList<>();
        if(properties.isUseUserTokenBalance()) {
            balances.getLlmTokenBalance(user);
            result.add(new Component("DIRECT_DEBIT", "user:token:llm:"+user, "", "balance", 0, 0, reserve));
        } else {
            String key="quota:llm:"+LocalDate.now()+":user:"+user;
            result.add(new Component("RESERVE_ADJUST", key, key+":epoch", LocalDate.now().toString(),
                    Duration.ofDays(Math.max(1, properties.getRetentionDays())).toMillis(), properties.getLlm().getDayMaxTokens(), reserve));
        }
        var budget=limits.getCurrentSettings().llmGlobalToken();
        addWindow(result,"minute",budget.minuteMax(),budget.minuteWindowSeconds(),reserve);
        addWindow(result,"day",budget.dayMax(),budget.dayWindowSeconds(),reserve);
        return result;
    }
    private void addWindow(List<Component> list,String name,long limit,long seconds,long reserve) {
        if(limit>0 && seconds>0) {
            String key="budget:llm:global:"+name;
            list.add(new Component("RESERVE_ADJUST",key,key+":epoch",UUID.randomUUID().toString(),seconds*1000,limit,reserve));
        }
    }
    public void reserve(String id, int maxOutput) {
        synchronized(reservationLocks[Math.floorMod(id.hashCode(),reservationLocks.length)]) {reserveLocked(id,maxOutput);}
    }
    private void reserveLocked(String id,int maxOutput) {
        LlmRequestAttempt initial=attempts.findById(id).orElseThrow();
        if(initial.getReservationStatus()!=ReservationStatus.NEW && maxOutput!=initial.getRequestedCompletionTokens()) {
            reconcile(id,"reservation parameters changed");throw new IllegalStateException("Reservation parameters conflict");
        }
        List<Component> planned=initial.getReservationStatus()==ReservationStatus.NEW ? components(initial,maxOutput) : List.of();
        boolean owner=Boolean.TRUE.equals(tx.execute(status -> {
            var a=attempts.lock(id).orElseThrow();
            if(a.getReservationStatus()!=ReservationStatus.NEW) return false;
            a.setRequestedCompletionTokens(maxOutput);
            a.setReservationJson(json(planned)); a.setReservationFingerprint(fingerprint(a.getReservationJson()));
            a.setReservationStatus(ReservationStatus.REQUESTED); a.setUpdatedAt(Instant.now()); attempts.save(a); return true;
        }));
        var a=attempts.findById(id).orElseThrow();
        if(a.getReservationStatus()==ReservationStatus.RESERVED) return;
        String receipt;
        try {
            receipt=owner ? redis.execute(reserveScript,List.of(receiptKey(id)),a.getReservationFingerprint(),a.getReservationJson())
                    : redis.opsForValue().get(receiptKey(id));
        } catch (RuntimeException e) {
            // A timeout does not establish whether Lua applied its writes.
            receipt=readReceiptOrNull(id);
            if(receipt==null) { reconcile(id,"reservation outcome unknown"); throw e; }
        }
        if(receipt==null) { reconcile(id,"missing reservation after request"); throw new IllegalStateException("Reservation requires reconciliation"); }
        acceptReservation(id,receipt);
    }
    private String readReceiptOrNull(String id) { return redis.opsForValue().get(receiptKey(id)); }
    private void acceptReservation(String id,String receipt) {
        try {
            var node=mapper.readTree(receipt); var a=attempts.findById(id).orElseThrow();
            if(!Objects.equals(node.path("reservationFingerprint").asText(),a.getReservationFingerprint())) throw new IllegalStateException("Reservation fingerprint conflict");
            boolean rejected="REJECTED".equals(node.path("phase").asText());
            if(!rejected && !"RESERVED".equals(node.path("phase").asText())) throw new IllegalStateException("Unexpected reservation state");
            tx.executeWithoutResult(status -> {
                var locked=attempts.lock(id).orElseThrow();
                locked.setReservationStatus(rejected?ReservationStatus.REJECTED:ReservationStatus.RESERVED);
                locked.setReservationJson(node.path("components").toString()); locked.setReceiptJson(receipt);
                locked.setUpdatedAt(Instant.now()); attempts.save(locked);
            });
            if(rejected) throw new RateLimitExceededException("LLM Token 额度不足",1);
        } catch(RateLimitExceededException e){throw e;}
        catch(Exception e){reconcile(id,"reservation conflict"); throw new IllegalStateException("Reservation requires reconciliation",e);}
    }
    public void freeze(String id, Integer prompt, Integer completion, int observedOutput, boolean finished, String diagnostic) {
        freeze(id,prompt,completion,observedOutput,finished,diagnostic,null);
    }
    public void freeze(String id, Integer prompt, Integer completion, int observedOutput, boolean finished, String diagnostic,String requestId) {
        freeze(id,prompt,completion,observedOutput,finished,diagnostic,requestId,null);
    }
    public void freeze(String id, Integer prompt, Integer completion, int observedOutput, boolean finished, String diagnostic,String requestId,String rawUsage) {
        tx.executeWithoutResult(status -> {
            var a=attempts.lock(id).orElseThrow();
            if(requestId!=null) a.setProviderRequestId(requestId);
            if(a.getFrozenTokens()!=null) {
                if(rawUsage!=null && !rawUsage.equals(a.getRawUsageJson())) a.setLateUsageJson(rawUsage);
                if(prompt!=null && !Objects.equals(prompt,a.getPromptTokens())) a.setLatePromptTokens(prompt);
                if(completion!=null && !Objects.equals(completion,a.getCompletionTokens())) a.setLateCompletionTokens(completion);
                attempts.save(a); return;
            }
            a.setRawUsageJson(rawUsage);
            a.setPromptTokens(prompt); a.setCompletionTokens(completion); a.setObservedOutputTokens(Math.max(0,observedOutput));
            long amount=a.isSent() ? (long)(prompt==null?a.getEstimatedPromptTokens():prompt)
                    +(completion==null?a.getObservedOutputTokens():completion) : 0;
            a.setFrozenTokens(amount); a.setUsageSource(prompt!=null && completion!=null?"ACTUAL":"ESTIMATED");
            a.setSettlementVersion(1);
            a.setMetricKey(metricKey(a));
            a.setSettlementFingerprint(fingerprint(a.getReservationJson()+"|1|"+amount+"|"+a.isSent()+"|"+a.getMetricKey()));
            a.setPhase(finished?Phase.FINISHED:Phase.INTERRUPTED); a.setDiagnostic(diagnostic);
            a.setUpdatedAt(Instant.now()); attempts.save(a);
        });
    }
    public void settle(String id) {
        var a=tx.execute(status -> {
            var locked=attempts.lock(id).orElseThrow();
            if(locked.getFrozenTokens()==null || locked.getSettlementStatus()==SettlementStatus.RECONCILE_REQUIRED) return null;
            if(locked.getSettlementStatus()!=SettlementStatus.SETTLED) locked.setSettlementStatus(SettlementStatus.APPLYING);
            locked.setUpdatedAt(Instant.now()); return attempts.save(locked);
        });
        if(a==null) return;
        if(a.getReservationStatus()==ReservationStatus.NEW || a.getReservationStatus()==ReservationStatus.REJECTED) {
            if(a.isSent()) {reconcile(id,"sent without reservation");return;}
            confirm(id,null); return;
        }
        String receipt=redis.opsForValue().get(receiptKey(id));
        if(receipt==null) {reconcile(id,"missing receipt; do not charge again");return;}
        try {
            var prior=mapper.readTree(receipt);
            if(a.getSettlementStatus()==SettlementStatus.SETTLED) { validateApplied(a,prior); return; }
            if("APPLIED".equals(prior.path("phase").asText())) validateApplied(a,prior);
            else {
                if(!"RESERVED".equals(prior.path("phase").asText())) throw new IllegalStateException("Missing reservation");
                String countKey=a.getMetricKey();
                receipt=redis.execute(settleScript,List.of(receiptKey(id)),a.getReservationFingerprint(),a.getSettlementFingerprint(),
                    Integer.toString(a.getSettlementVersion()),Long.toString(a.getFrozenTokens()),countKey,Boolean.toString(a.isSent()));
                validateApplied(a,mapper.readTree(receipt));
            }
        } catch(Exception e) {
            // Re-read after ambiguous script completion; never substitute a new debit.
            String recovered=readReceiptOrNull(id);
            try { validateApplied(a,mapper.readTree(Objects.requireNonNull(recovered))); receipt=recovered; }
            catch(Exception ignored) { reconcile(id,"settlement receipt unavailable or conflicting"); return; }
        }
        confirm(id,receipt);
    }
    private String metricKey(LlmRequestAttempt a) {
        if(!hasComponents(a)) return "";
        return a.getReservationJson().contains("DIRECT_DEBIT")?"quota:llm:requests:user:"+a.getUserId():"quota:llm:requests:"+a.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate()+":user:"+a.getUserId();
    }
    private void validateApplied(LlmRequestAttempt a,com.fasterxml.jackson.databind.JsonNode node) {
        if(node==null || !"APPLIED".equals(node.path("phase").asText())
            || !a.getReservationFingerprint().equals(node.path("reservationFingerprint").asText())
            || !a.getSettlementFingerprint().equals(node.path("fingerprint").asText())
            || a.getSettlementVersion()!=node.path("version").asInt() || a.getFrozenTokens()!=node.path("amount").asLong())
            throw new IllegalStateException("Settlement fingerprint conflict");
    }
    private void confirm(String id,String receipt) {
        tx.executeWithoutResult(status -> {
            var a=attempts.lock(id).orElseThrow();
            if(a.getSettlementStatus()==SettlementStatus.SETTLED) return;
            if(receipt!=null) {
                try {validateApplied(a,mapper.readTree(receipt));}catch(Exception e){throw new IllegalStateException(e);}
            }
            if(a.isSent() && a.getFrozenTokens()>0 && hasComponents(a)) aggregate(a,receipt);
            a.setReceiptJson(receipt); a.setSettlementStatus(SettlementStatus.SETTLED); a.setUpdatedAt(Instant.now()); attempts.save(a);
        });
    }
    private void aggregate(LlmRequestAttempt a,String receipt) {
        users.lock(Long.parseLong(a.getUserId())).orElseThrow();
        LocalDate day=a.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
        var record=records.findByUserIdAndRecordDateAndTokenTypeAndChangeType(a.getUserId(),day,UserTokenRecord.TokenType.LLM,UserTokenRecord.ChangeType.CONSUME).orElseGet(()->{
            var r=new UserTokenRecord(); r.setUserId(a.getUserId());r.setRecordDate(day);r.setTokenType(UserTokenRecord.TokenType.LLM);
            r.setChangeType(UserTokenRecord.ChangeType.CONSUME);r.setAmount(0L);r.setRequestCount(0L);r.setReason("LLM attempt settlement");return r;
        });
        record.setAmount(record.getAmount()+a.getFrozenTokens()); record.setRequestCount(record.getRequestCount()+1);
        records.save(record);
    }
    private boolean hasComponents(LlmRequestAttempt a) {
        try { return a.getReservationJson()!=null && mapper.readTree(a.getReservationJson()).size()>0; }
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public void reconcile(String id,String diagnostic) {
        tx.executeWithoutResult(status -> {
            var a=attempts.lock(id).orElseThrow();
            if(a.getSettlementStatus()!=SettlementStatus.SETTLED) a.setSettlementStatus(SettlementStatus.RECONCILE_REQUIRED);
            a.setDiagnostic(diagnostic); a.setUpdatedAt(Instant.now()); attempts.save(a);
        });
        log.error("LLM settlement reconciliation required: attempt={}, reason={}",id,diagnostic);
        if(metrics!=null) metrics.counter("llm.settlement.reconcile").increment();
    }
}
