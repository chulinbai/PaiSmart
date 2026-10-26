package com.yizhaoqi.smartpai.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClientResponseException;

public final class LlmResponseValidator {
    private LlmResponseValidator() {}
    public enum Kind { PROTOCOL, EMPTY, TRANSIENT, REJECTED, UNKNOWN_REASON, TRUNCATED_TOOL, LENGTH, STRUCTURE, CONFIG, TIMEOUT, CANCELLED }
    public static final class Failure extends RuntimeException {
        private final Kind kind;
        private final String reason;
        public Failure(Kind kind, String reason) { super(reason); this.kind = kind; this.reason = reason; }
        public Kind kind() { return kind; }
        public String reason() { return reason; }
    }
    public static Failure protocol(String message) { return new Failure(Kind.PROTOCOL, message); }
    public static boolean length(String reason) {
        return Set.of("length", "max_tokens", "max_output_tokens").contains(normalize(reason));
    }
    public static String normalize(String reason) { return reason == null ? "unknown" : reason.trim().toLowerCase(Locale.ROOT); }
    public static void validate(LlmResult result) {
        if (!result.protocolComplete()) throw protocol("Incomplete provider response");
        String reason = normalize(result.finishReason());
        if (Set.of("content_filter", "safety", "blocked").contains(reason)) throw new Failure(Kind.REJECTED, reason);
        if ("insufficient_system_resource".equals(reason)) throw new Failure(Kind.TRANSIENT, reason);
        if (!Set.of("stop", "end_turn", "completed", "tool_streamed", "tool_calls", "function_call").contains(reason) && !length(reason))
            throw new Failure(Kind.UNKNOWN_REASON, reason);
        if (length(reason) && !result.toolCalls().isEmpty()) throw new Failure(Kind.TRUNCATED_TOOL, reason);
        boolean expectsTools = Set.of("tool_calls", "function_call").contains(reason);
        if (expectsTools != !result.toolCalls().isEmpty()) throw protocol("Tool calls and finish reason disagree");
        if (result.toolCalls().isEmpty() && result.content().isBlank()) throw new Failure(Kind.EMPTY, "Empty model answer");
    }
    public static boolean retryable(Throwable error) {
        Throwable e = unwrap(error);
        if (e instanceof Failure f) return Set.of(Kind.PROTOCOL, Kind.EMPTY, Kind.TRANSIENT, Kind.TIMEOUT).contains(f.kind());
        if (e instanceof WebClientResponseException response) {
            int status = response.getStatusCode().value();
            return status == 408 || status == 429 || status >= 500;
        }
        return e instanceof java.io.IOException || e instanceof java.util.concurrent.TimeoutException
                || e instanceof org.springframework.web.reactive.function.client.WebClientRequestException;
    }
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.ExecutionException || current instanceof java.util.concurrent.CompletionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }
    public static boolean unsupportedVision(Throwable error) {
        Throwable e = unwrap(error);
        if (!(e instanceof WebClientResponseException response) || (response.getStatusCode().value() != 400 && response.getStatusCode().value() != 422)) return false;
        String body = response.getResponseBodyAsString().toLowerCase(Locale.ROOT);
        return (body.contains("image") || body.contains("vision"))
                && (body.contains("not support") || body.contains("unsupported") || body.contains("does not support"));
    }
}
