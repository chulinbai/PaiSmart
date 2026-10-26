package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "agent_workspaces", indexes = {
        @Index(name = "idx_aw_owner", columnList = "owner_user_id"),
        @Index(name = "idx_aw_owner_default", columnList = "owner_user_id,default_workspace")
})
public class AgentWorkspace {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "org_tag", length = 255)
    private String orgTag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkspaceType type = WorkspaceType.KNOWLEDGE;

    @Column(nullable = false, length = 255)
    private String name;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String instructions;

    @Column(name = "binding_type", length = 64)
    private String bindingType;

    @Column(name = "binding_ref", length = 2048)
    private String bindingRef;

    @Column(name = "default_workspace", nullable = false)
    private boolean defaultWorkspace;

    @Version
    private Long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum WorkspaceType { KNOWLEDGE, LOCAL, GIT, REMOTE }
}
