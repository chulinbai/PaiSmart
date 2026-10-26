package com.yizhaoqi.smartpai.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

@Data @Entity
@Table(name = "llm_request_attempt", indexes = {
    @Index(name="idx_attempt_generation", columnList="generation_id"),
    @Index(name="idx_attempt_settlement", columnList="settlement_status,updated_at")})
public class LlmRequestAttempt {
    @Id @Column(length=36) private String id;
    @Column(name="generation_id", length=64) private String generationId;
    @Column(length=128) private String jobId;
    @Column(nullable=false, length=64) private String logicalCallId;
    @Column(length=64) private String userId;
    @Column(length=64) private String provider;
    @Column(length=255) private String model;
    @Column(length=255) private String providerRequestId;
    @Column(columnDefinition="TEXT") private String providerSnapshotJson;
    @Column(columnDefinition="TEXT") private String rawUsageJson;
    @Column(columnDefinition="TEXT") private String lateUsageJson;
    @Column(length=64) private String purpose;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) private Phase phase = Phase.PREPARED;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=24) private ReservationStatus reservationStatus = ReservationStatus.NEW;
    @Enumerated(EnumType.STRING) @Column(name="settlement_status", nullable=false, length=32) private SettlementStatus settlementStatus = SettlementStatus.PENDING;
    private int estimatedPromptTokens;
    private int requestedCompletionTokens;
    private int observedOutputTokens;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latePromptTokens;
    private Integer lateCompletionTokens;
    private Long frozenTokens;
    private String usageSource;
    private int settlementVersion;
    private String metricKey;
    @Column(length=64) private String reservationFingerprint;
    @Column(length=64) private String settlementFingerprint;
    @Column(columnDefinition="LONGTEXT") private String reservationJson;
    @Column(columnDefinition="LONGTEXT") private String receiptJson;
    private boolean extraRequest;
    private boolean sent;
    @Column(length=1000) private String diagnostic;
    @Column(nullable=false) private Instant createdAt = Instant.now();
    @Column(name="updated_at", nullable=false) private Instant updatedAt = Instant.now();
    public enum Phase { PREPARED, SENDING, RECEIVING, FINISHED, INTERRUPTED }
    public enum ReservationStatus { NEW, REQUESTED, RESERVED, REJECTED, UNKNOWN }
    public enum SettlementStatus { PENDING, APPLYING, SETTLED, RECONCILE_REQUIRED }
}
