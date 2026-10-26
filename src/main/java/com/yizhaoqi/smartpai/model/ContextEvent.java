package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "context_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_context_thread_sequence", columnNames = {"thread_id", "event_sequence"}),
        @UniqueConstraint(name = "uk_context_bootstrap_source", columnNames = {"thread_id", "source_key"})
}, indexes = {
        @Index(name = "idx_context_thread_created", columnList = "thread_id,created_at"),
        @Index(name = "idx_context_workspace", columnList = "workspace_id"),
        @Index(name = "idx_context_run", columnList = "run_id")
})
public class ContextEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false, length = 64)
    private String threadId;

    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "event_sequence", nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    @Column(length = 32)
    private String role;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "artifact_id", length = 36)
    private String artifactId;

    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean pinned;

    @Lob
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    @Column(name = "source_key", length = 128)
    private String sourceKey;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum EventType {
        MESSAGE, TOOL_CALL, TOOL_RESULT, ERROR, SYSTEM_INSTRUCTION, MEMORY_CHANGE,
        COMPACTION_BOUNDARY, RESET_BOUNDARY, CHECKPOINT, PLAN, COMMAND, FILE_CHANGE, APPROVAL
    }
}
