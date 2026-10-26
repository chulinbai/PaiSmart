package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity
@Table(name="agent_tool_execution",uniqueConstraints=@UniqueConstraint(name="uk_tool_execution_identity",columnNames={"generation_id","logical_call_id","tool_call_id"}))
public class AgentToolExecution {
    @Id @Column(length=64) private String id;
    @Column(name="generation_id",nullable=false,length=64) private String generationId;
    @Column(name="logical_call_id",nullable=false,length=64) private String logicalCallId;
    @Column(name="tool_call_id",nullable=false,length=255) private String toolCallId;
    @Column(nullable=false,length=128) private String toolName;
    @Column(nullable=false,length=64) private String argumentsFingerprint;
    @Lob @Column(columnDefinition="LONGTEXT") private String argumentsJson;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Status status=Status.PREPARED;
    @Lob @Column(columnDefinition="LONGTEXT") private String resultJson;
    @Lob @Column(columnDefinition="LONGTEXT") private String referencesJson;
    @Column(length=1000) private String diagnostic;
    private Instant createdAt=Instant.now();
    private Instant updatedAt=Instant.now();
    public enum Status { PREPARED, EXECUTING, SUCCEEDED, FAILED }
}
