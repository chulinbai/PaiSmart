package com.yizhaoqi.smartpai.service;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** One logical call keeps its provider and identity across bounded recovery attempts. */
public record LlmCallContext(String generationId, String jobId, String logicalCallId, String purpose,
                             boolean extra, long deadlineNanos, Object gate, BooleanSupplier active,
                             ModelProviderConfigService.ActiveProviderView provider) {
    public static LlmCallContext background(String jobId, String purpose, ModelProviderConfigService.ActiveProviderView provider) {
        return new LlmCallContext(null, jobId, UUID.randomUUID().toString(), purpose, false,
            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120), new Object(), () -> true, provider);
    }
    public LlmCallContext recovery(String purpose) {
        return new LlmCallContext(generationId, jobId, logicalCallId, purpose, true, deadlineNanos, gate, active, provider);
    }
    public boolean valid() { return active.getAsBoolean() && System.nanoTime() < deadlineNanos; }
}
