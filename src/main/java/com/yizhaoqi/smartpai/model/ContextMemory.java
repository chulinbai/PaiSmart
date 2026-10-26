package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "context_memories", uniqueConstraints = @UniqueConstraint(
        name = "uk_context_memory_scope", columnNames = {"owner_user_id", "memory_scope", "scope_id", "memory_key"}),
        indexes = @Index(name = "idx_context_memory_lookup", columnList = "owner_user_id,memory_scope,scope_id,active"))
public class ContextMemory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;
    @Enumerated(EnumType.STRING) @Column(name = "memory_scope", nullable = false, length = 16)
    private MemoryScope scope;
    @Column(name = "scope_id", nullable = false, length = 64)
    private String scopeId;
    @Column(name = "memory_key", nullable = false, length = 128)
    private String key;
    @Lob @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String content;
    @Column(name = "token_estimate", nullable = false)
    private int tokenEstimate;
    @Column(nullable = false)
    private boolean active = true;
    @CreationTimestamp private LocalDateTime createdAt;
    @UpdateTimestamp private LocalDateTime updatedAt;
    public enum MemoryScope { USER, WORKSPACE, THREAD }
}
