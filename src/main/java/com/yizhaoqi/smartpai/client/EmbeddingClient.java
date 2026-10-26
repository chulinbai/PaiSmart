package com.yizhaoqi.smartpai.client;

import org.springframework.stereotype.Component;

import java.util.List;

// 兼容旧类名；实际请求统一委托给 MultiEmbeddingClient，避免再启用第二套 embedding 模型。
@Component
@Deprecated
public class EmbeddingClient {

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    private final MultiEmbeddingClient multiEmbeddingClient;

    public EmbeddingClient(MultiEmbeddingClient multiEmbeddingClient) {
        this.multiEmbeddingClient = multiEmbeddingClient;
    }

    public List<float[]> embed(List<String> texts) {
        return embedWithUsage(texts, "system", UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId) {
        return embedWithUsage(texts, requesterId, UsageType.UPLOAD).vectors();
    }

    public List<float[]> embed(List<String> texts, String requesterId, UsageType usageType) {
        return embedWithUsage(texts, requesterId, usageType).vectors();
    }

    public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
        MultiEmbeddingClient.UsageType multimodalUsageType = usageType == UsageType.QUERY
                ? MultiEmbeddingClient.UsageType.QUERY
                : MultiEmbeddingClient.UsageType.UPLOAD;
        MultiEmbeddingClient.EmbeddingUsageResult result = multiEmbeddingClient.embedWithUsage(
                texts.stream().map(MultiEmbeddingClient.EmbeddingInput::text).toList(),
                requesterId,
                multimodalUsageType
        );
        return new EmbeddingUsageResult(result.vectors(), result.totalTokens(), result.modelVersion());
    }

    public String currentModelVersion() {
        return multiEmbeddingClient.currentModelVersion();
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }
}
