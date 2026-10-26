package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "context_snapshots", uniqueConstraints =
        @UniqueConstraint(name = "uk_context_snapshot_version", columnNames = {"thread_id", "snapshot_version"}),
        indexes = @Index(name = "idx_context_snapshot_thread", columnList = "thread_id,created_at"))
public class ContextSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "thread_id", nullable = false, length = 64)
    private String threadId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;
    @Column(name = "snapshot_version", nullable = false)
    private long version;
    @Column(name = "covered_through_sequence", nullable = false)
    private long coveredThroughSequence;
    @Lob @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String summary;
    @Lob @Column(name = "structured_summary_json", columnDefinition = "LONGTEXT")
    private String structuredSummaryJson;
    @Column(name = "input_hash", nullable = false, length = 64)
    private String inputHash;
    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;
    @Column(nullable = false)
    private boolean degraded;
    @CreationTimestamp @Column(name = "created_at")
    private LocalDateTime createdAt;
}
