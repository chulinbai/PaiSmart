package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_runs", indexes = {
        @Index(name = "idx_agent_run_thread", columnList = "thread_id"),
        @Index(name = "idx_agent_run_status", columnList = "run_status")
})
public class AgentRun {
    @Id @Column(name = "generation_id", length = 64)
    private String generationId;
    @Column(name = "thread_id", nullable = false, length = 64)
    private String threadId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING) @Column(name = "run_status", nullable = false, length = 24)
    private RunStatus status;
    @Column(name = "context_version", nullable = false)
    private long contextVersion;
    @Column(length = 64) private String provider;
    @Column(length = 255) private String model;
    @Column(name = "prompt_tokens", nullable = false) private int promptTokens;
    @Column(name = "completion_tokens", nullable = false) private int completionTokens;
    @Lob @Column(name = "checkpoint_json", columnDefinition = "LONGTEXT") private String checkpointJson;
    @Column(name = "finish_reason", length = 64) private String finishReason;
    private int recoveryAttempts;
    private boolean cancelRequested;
    @Column(length=36) private String finalizationToken;
    @Lob @Column(columnDefinition="LONGTEXT") private String finalizationIntent;
    private int finalizationAttempts;
    private LocalDateTime finalizationStartedAt;
    private LocalDateTime nextRecoveryAt;
    @Column(length=1000) private String persistenceError;
    @CreationTimestamp @Column(name = "created_at") private LocalDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private LocalDateTime updatedAt;
    public enum RunStatus { CREATED, RUNNING, FINALIZING, COMPLETED, INCOMPLETE, REJECTED, FAILED, CANCELLED, RECOVERABLE }
}
