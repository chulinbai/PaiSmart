package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatGenerationStateService {

    private static final Logger logger = LoggerFactory.getLogger(ChatGenerationStateService.class);
    private static final Duration GENERATION_TTL = Duration.ofMinutes(30);
    private static final Duration CONVERSATION_RECOVERY_TTL = Duration.ofMinutes(30);
    private static final TypeReference<Map<String, Map<String, Object>>> REFERENCE_MAP_TYPE =
            new TypeReference<Map<String, Map<String, Object>>>() {};

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.yizhaoqi.smartpai.repository.AgentRunRepository runs;
    @org.springframework.beans.factory.annotation.Autowired private com.yizhaoqi.smartpai.repository.ContextEventRepository events;

    public ChatGenerationStateService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public GenerationSnapshot createGeneration(String userId, String conversationId, String question) {
        String generationId = UUID.randomUUID().toString();
        String now = LocalDateTime.now().toString();
        GenerationMeta meta = new GenerationMeta(
                generationId, userId, conversationId, question, GenerationStatus.STREAMING,
                now, now, null, "unknown", false, 0
        );

        // 写入顺序：先把可读子项准备好，最后再发布 active key，避免读取者拿到 active key 后却查不到 meta/content。
        redisTemplate.delete(referenceKey(generationId));
        redisTemplate.opsForValue().set(contentKey(generationId), "", GENERATION_TTL);
        writeMeta(meta);
        publishConversationRecovery(meta);
        redisTemplate.opsForValue().set(activeGenerationKey(userId), generationId, GENERATION_TTL);
        return toSnapshot(meta, "", Collections.emptyMap());
    }

    public void appendChunk(String generationId, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            touch(generationId);
            return;
        }

        redisTemplate.opsForValue().append(contentKey(generationId), chunk);
        touch(generationId);
    }

    public void updateReferenceMappings(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        try {
            if (referenceMappings == null || referenceMappings.isEmpty()) {
                redisTemplate.delete(referenceKey(generationId));
            } else {
                redisTemplate.opsForValue().set(
                        referenceKey(generationId),
                        objectMapper.writeValueAsString(referenceMappings),
                        GENERATION_TTL
                );
            }
            touch(generationId);
        } catch (Exception e) {
            logger.warn("保存生成态引用映射失败: generationId={}", generationId, e);
        }
    }

    public void markCompleted(String generationId, Map<String, Map<String, Object>> referenceMappings) {
        markCompleted(generationId, referenceMappings, "unknown", false, 0);
    }

    public void markCompleted(String generationId,
                              Map<String, Map<String, Object>> referenceMappings,
                              String finishReason,
                              boolean truncated,
                              int continuationAttempts) {
        updateTerminalState(generationId, GenerationStatus.COMPLETED, null, referenceMappings,
                finishReason, truncated, continuationAttempts);
    }

    public void markFailed(String generationId, String errorMessage) {
        markFailed(generationId, errorMessage, "unknown", false, 0);
    }

    public void markFailed(String generationId,
                           String errorMessage,
                           String finishReason,
                           boolean truncated,
                           int continuationAttempts) {
        updateTerminalState(generationId, GenerationStatus.FAILED, errorMessage, null,
                finishReason, truncated, continuationAttempts);
    }

    public void markCancelled(String generationId) {
        updateTerminalState(generationId, GenerationStatus.CANCELLED, null, null,
                "cancelled", false, 0);
    }

    public Optional<GenerationSnapshot> getGeneration(String generationId) {
        if(finalizers!=null) {
            var finalizer=finalizers.getIfAvailable();
            var pending=finalizer==null?Optional.<ChatRunFinalizer.Intent>empty():finalizer.pendingIntent(generationId);
            if(pending.isPresent()) return Optional.of(fromIntent(pending.get(),GenerationStatus.FINALIZING));
        }
        if(runs!=null) {
            var run=runs.findById(generationId).orElse(null);
            if(run!=null && run.getStatus()==com.yizhaoqi.smartpai.model.AgentRun.RunStatus.RECOVERABLE) {
                String question=events.findTopByRunIdAndRoleOrderBySequenceAsc(generationId,"user").map(com.yizhaoqi.smartpai.model.ContextEvent::getContent).orElse("");
                String content="";Map<String,Map<String,Object>> references=Map.of();
                try {content=Optional.ofNullable(redisTemplate.opsForValue().get(contentKey(generationId))).orElse("");references=readReferenceMappings(generationId);}catch(RuntimeException ignored){}
                return Optional.of(new GenerationSnapshot(generationId,run.getUserId().toString(),run.getThreadId(),question,GenerationStatus.RECOVERABLE,
                        content,String.valueOf(run.getCreatedAt()),String.valueOf(run.getUpdatedAt()),"服务已重启，请重新生成","service_restarted",true,0,references));
            }
            if(run!=null && run.getFinalizationIntent()!=null) {
                try {
                    var intent=objectMapper.readValue(run.getFinalizationIntent(),ChatRunFinalizer.Intent.class);
                    boolean saving=run.getStatus()==com.yizhaoqi.smartpai.model.AgentRun.RunStatus.FINALIZING;
                    return Optional.of(fromIntent(intent,saving?GenerationStatus.FINALIZING:GenerationStatus.valueOf(run.getStatus().name())));
                }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid persisted answer",e);}
            }
        }
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return Optional.empty();
        }

        String content = Optional.ofNullable(redisTemplate.opsForValue().get(contentKey(generationId))).orElse("");
        Map<String, Map<String, Object>> references = readReferenceMappings(generationId);
        return Optional.of(toSnapshot(meta, content, references));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ChatRunFinalizer> finalizers;

    public void saveFinalSnapshot(ChatRunFinalizer.Intent intent) {
        String now=LocalDateTime.now().toString();
        redisTemplate.opsForValue().set(contentKey(intent.generationId()),intent.answer(),GENERATION_TTL);
        updateReferenceMappings(intent.generationId(),intent.references());
        GenerationMeta meta=new GenerationMeta(intent.generationId(),intent.userId(),intent.conversationId(),intent.question(),
                GenerationStatus.valueOf(intent.status().name()),now,now,intent.diagnostic(),intent.finishReason(),
                intent.status()==com.yizhaoqi.smartpai.model.AgentRun.RunStatus.INCOMPLETE,0);
        writeMeta(meta);clearActiveGeneration(intent.userId(),intent.generationId());
        clearConversationRecovery(intent.conversationId(),intent.generationId());
    }
    private GenerationSnapshot fromIntent(ChatRunFinalizer.Intent i,GenerationStatus status) {
        String created=java.time.Instant.ofEpochMilli(i.createdAt()).toString();
        String persistence=status==GenerationStatus.FINALIZING?(System.currentTimeMillis()-i.createdAt()>30000?"DELAYED":"PENDING"):"SAVED";
        return new GenerationSnapshot(i.generationId(),i.userId(),i.conversationId(),i.question(),status,i.answer(),created,created,
                i.diagnostic(),i.finishReason(),i.status()==com.yizhaoqi.smartpai.model.AgentRun.RunStatus.INCOMPLETE,0,i.references(),i.token(),i.lastSequence(),persistence);
    }

    public Optional<GenerationSnapshot> getGenerationForUser(String generationId, String userId) {
        return getGeneration(generationId).filter(snapshot -> snapshot.userId().equals(userId));
    }

    public Optional<GenerationSnapshot> getActiveGenerationForUser(String userId) {
        if(runs!=null) {
            var run=runs.findTopByUserIdOrderByCreatedAtDesc(Long.valueOf(userId));
            if(run.isPresent()) return ChatRunFinalizer.terminal(run.get().getStatus())?Optional.empty():getGenerationForUser(run.get().getGenerationId(),userId);
        }
        String generationId = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        return getGenerationForUser(generationId, userId);
    }

    public Optional<GenerationSnapshot> getRecoverableGenerationForConversation(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank() || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        if(runs!=null) {
            var run=runs.findTopByThreadIdAndUserIdOrderByCreatedAtDesc(conversationId,Long.valueOf(userId));
            if(run.isPresent()) return ChatRunFinalizer.terminal(run.get().getStatus())?Optional.empty():getGenerationForUser(run.get().getGenerationId(),userId);
        }

        String key = conversationRecoveryKey(conversationId);
        String generationId = redisTemplate.opsForValue().get(key);
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }

        Optional<GenerationSnapshot> candidate = getGeneration(generationId);
        if (candidate.isEmpty()) {
            redisTemplate.delete(key);
            return Optional.empty();
        }

        GenerationSnapshot snapshot = candidate.get();
        if (!userId.equals(snapshot.userId())) {
            return Optional.empty();
        }
        if (!isRecoverable(snapshot.status())) {
            clearConversationRecovery(conversationId, generationId);
            return Optional.empty();
        }

        return Optional.of(snapshot);
    }

    private void updateTerminalState(String generationId,
                                     GenerationStatus status,
                                     String errorMessage,
                                     Map<String, Map<String, Object>> referenceMappings,
                                     String finishReason,
                                     boolean truncated,
                                     int continuationAttempts) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        if (referenceMappings != null) {
            updateReferenceMappings(generationId, referenceMappings);
        } else {
            touch(generationId);
        }

        String now = LocalDateTime.now().toString();
        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                status,
                meta.createdAt(),
                now,
                errorMessage,
                normalizeFinishReason(finishReason),
                truncated,
                Math.max(continuationAttempts, 0)
        );
        writeMeta(updated);
        if (status == GenerationStatus.COMPLETED || status == GenerationStatus.CANCELLED) {
            clearConversationRecovery(meta.conversationId(), generationId);
        } else {
            publishConversationRecovery(updated);
        }
        clearActiveGeneration(meta.userId(), generationId);
    }

    private void touch(String generationId) {
        GenerationMeta meta = readMeta(generationId);
        if (meta == null) {
            return;
        }

        GenerationMeta updated = new GenerationMeta(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                meta.createdAt(),
                LocalDateTime.now().toString(),
                meta.errorMessage(),
                normalizeFinishReason(meta.finishReason()),
                meta.truncated(),
                meta.continuationAttempts()
        );
        writeMeta(updated);
        redisTemplate.expire(contentKey(generationId), GENERATION_TTL);
        redisTemplate.expire(referenceKey(generationId), GENERATION_TTL);
        publishConversationRecovery(updated);
        redisTemplate.opsForValue().set(activeGenerationKey(meta.userId()), generationId, GENERATION_TTL);
    }

    private void clearActiveGeneration(String userId, String generationId) {
        String current = redisTemplate.opsForValue().get(activeGenerationKey(userId));
        if (generationId.equals(current)) {
            redisTemplate.delete(activeGenerationKey(userId));
        }
    }

    private void writeMeta(GenerationMeta meta) {
        try {
            redisTemplate.opsForValue().set(metaKey(meta.generationId()), objectMapper.writeValueAsString(meta), GENERATION_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("保存生成态元数据失败", e);
        }
    }

    private GenerationMeta readMeta(String generationId) {
        String raw = redisTemplate.opsForValue().get(metaKey(generationId));
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(raw, GenerationMeta.class);
        } catch (Exception e) {
            logger.warn("解析生成态元数据失败: generationId={}", generationId, e);
            return null;
        }
    }

    private Map<String, Map<String, Object>> readReferenceMappings(String generationId) {
        String raw = redisTemplate.opsForValue().get(referenceKey(generationId));
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(raw, REFERENCE_MAP_TYPE);
        } catch (Exception e) {
            logger.warn("解析生成态引用映射失败: generationId={}", generationId, e);
            return Collections.emptyMap();
        }
    }

    private GenerationSnapshot toSnapshot(GenerationMeta meta,
                                          String content,
                                          Map<String, Map<String, Object>> referenceMappings) {
        return new GenerationSnapshot(
                meta.generationId(),
                meta.userId(),
                meta.conversationId(),
                meta.question(),
                meta.status(),
                content,
                meta.createdAt(),
                meta.updatedAt(),
                meta.errorMessage(),
                normalizeFinishReason(meta.finishReason()),
                meta.truncated(),
                meta.continuationAttempts(),
                referenceMappings == null ? Collections.emptyMap() : referenceMappings
        );
    }

    private String normalizeFinishReason(String finishReason) {
        return finishReason == null || finishReason.isBlank() ? "unknown" : finishReason;
    }

    private String metaKey(String generationId) {
        return "chat:generation:" + generationId + ":meta";
    }

    private String contentKey(String generationId) {
        return "chat:generation:" + generationId + ":content";
    }

    private String referenceKey(String generationId) {
        return "chat:generation:" + generationId + ":refs";
    }

    private String activeGenerationKey(String userId) {
        return "chat:user:" + userId + ":active_generation";
    }

    private void publishConversationRecovery(GenerationMeta meta) {
        if (meta == null || meta.conversationId() == null || meta.conversationId().isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(
                conversationRecoveryKey(meta.conversationId()),
                meta.generationId(),
                CONVERSATION_RECOVERY_TTL
        );
    }

    private void clearConversationRecovery(String conversationId, String generationId) {
        if (conversationId == null || conversationId.isBlank() || generationId == null || generationId.isBlank()) {
            return;
        }
        String key = conversationRecoveryKey(conversationId);
        String current = redisTemplate.opsForValue().get(key);
        if (generationId.equals(current)) {
            redisTemplate.delete(key);
        }
    }

    private boolean isRecoverable(GenerationStatus status) {
        return status == GenerationStatus.STREAMING || status == GenerationStatus.FINALIZING || status == GenerationStatus.RECOVERABLE;
    }

    private String conversationRecoveryKey(String conversationId) {
        return "chat:conversation:" + conversationId + ":recovery_generation";
    }

    public enum GenerationStatus {
        STREAMING,
        FINALIZING,
        INCOMPLETE,
        REJECTED,
        RECOVERABLE,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record GenerationMeta(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String createdAt,
            String updatedAt,
            String errorMessage,
            String finishReason,
            boolean truncated,
            int continuationAttempts
    ) {
    }

    public record GenerationSnapshot(
            String generationId,
            String userId,
            String conversationId,
            String question,
            GenerationStatus status,
            String content,
            String createdAt,
            String updatedAt,
            String errorMessage,
            String finishReason,
            boolean truncated,
            int continuationAttempts,
            Map<String, Map<String, Object>> referenceMappings,
            String token,
            long lastSequence,
            String persistenceStatus
    ) {
        public GenerationSnapshot(String generationId,String userId,String conversationId,String question,GenerationStatus status,
                String content,String createdAt,String updatedAt,String errorMessage,String finishReason,boolean truncated,
                int continuationAttempts,Map<String,Map<String,Object>> referenceMappings) {
            this(generationId,userId,conversationId,question,status,content,createdAt,updatedAt,errorMessage,finishReason,truncated,continuationAttempts,referenceMappings,null,0,null);
        }
    }
}
