package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "context_artifacts", indexes = {
        @Index(name = "idx_context_artifact_owner", columnList = "owner_user_id"),
        @Index(name = "idx_context_artifact_workspace", columnList = "workspace_id"),
        @Index(name = "idx_context_artifact_expiry", columnList = "expires_at")
})
public class ContextArtifact {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;
    @Column(name = "workspace_id", nullable = false, length = 36)
    private String workspaceId;
    @Column(nullable = false, length = 128)
    private String bucket;
    @Column(name = "object_key", nullable = false, length = 1024)
    private String objectKey;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(name = "content_size", nullable = false)
    private long size;
    @Column(name = "mime_type", nullable = false, length = 255)
    private String mimeType;
    @Lob @Column(columnDefinition = "LONGTEXT")
    private String preview;
    @Column(nullable = false)
    private boolean pinned;
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;
    @CreationTimestamp @Column(name = "created_at")
    private LocalDateTime createdAt;
}
