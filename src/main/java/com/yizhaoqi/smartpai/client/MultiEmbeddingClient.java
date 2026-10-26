package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.RateLimitService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class MultiEmbeddingClient {

    public enum UsageType {
        UPLOAD,
        QUERY
    }

    private static final Logger logger = LoggerFactory.getLogger(MultiEmbeddingClient.class);

    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final UsageQuotaService usageQuotaService;

    @Value("${multimodal.embedding.api.url:http://127.0.0.1:11435/v1/embeddings}")
    private String apiUrl;

    @Value("${multimodal.embedding.api.key:}")
    private String apiKey;

    @Value("${multimodal.embedding.api.model:MedAIBase/Qwen3-VL-Embedding:2b}")
    private String model;

    @Value("${multimodal.embedding.api.dimension:2048}")
    private Integer dimension;

    @Value("${multimodal.embedding.api.batch-size:4}")
    private int batchSize;

    @Value("${multimodal.embedding.api.request-timeout-seconds:300}")
    private long requestTimeoutSeconds;

    @Value("${multimodal.embedding.api.max-in-memory-mb:128}")
    private int maxInMemoryMb;

    public MultiEmbeddingClient(ObjectMapper objectMapper,
                                RateLimitService rateLimitService,
                                UsageQuotaService usageQuotaService) {
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.usageQuotaService = usageQuotaService;
    }

    @PostConstruct
    public void init() {
        logger.info("MultiEmbeddingClient 初始化 - 模型: {}, 维度: {}, API地址: {}, batchSize: {}",
                model, dimension, apiUrl, batchSize);
    }

    public List<float[]> embedText(List<String> texts, String requesterId, UsageType usageType) {
        List<EmbeddingInput> inputs = texts.stream()
                .map(EmbeddingInput::text)
                .toList();
        return embedWithUsage(inputs, requesterId, usageType).vectors();
    }

    public EmbeddingUsageResult embedWithUsage(List<EmbeddingInput> inputs, String requesterId, UsageType usageType) {
        if (inputs == null || inputs.isEmpty()) {
            return new EmbeddingUsageResult(List.of(), 0, currentModelVersion());
        }

        try {
            String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
            WebClient client = buildClient();
            int effectiveBatchSize = Math.max(1, batchSize);
            List<float[]> all = new ArrayList<>(inputs.size());
            int totalTokens = 0;

            for (int start = 0; start < inputs.size(); start += effectiveBatchSize) {
                int end = Math.min(start + effectiveBatchSize, inputs.size());
                List<EmbeddingInput> batch = inputs.subList(start, end);
                List<String> billableTexts = batch.stream()
                        .map(EmbeddingInput::billableText)
                        .toList();
                UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                        ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, billableTexts)
                        : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, billableTexts);

                try {
                    String response = callApiOnce(client, batch);
                    EmbeddingApiResponse parsed = parseEmbeddingResponse(response, billableTexts);
                    usageQuotaService.settleReservation(reservation, parsed.totalTokens());
                    all.addAll(parsed.vectors());
                    totalTokens += parsed.totalTokens();
                } catch (Exception e) {
                    usageQuotaService.abortReservation(reservation);
                    throw e;
                }
            }

            if (all.size() != inputs.size()) {
                throw new RuntimeException(String.format(
                        "多模态 Embedding 返回数量不匹配: inputs=%d, vectors=%d",
                        inputs.size(), all.size()));
            }
            return new EmbeddingUsageResult(all, totalTokens, currentModelVersion());
        } catch (WebClientResponseException e) {
            logger.error("多模态 Embedding API 调用失败 - 状态码: {}, 响应: {}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new RuntimeException(String.format(
                    "多模态向量生成失败 - API错误: HTTP %d - %s",
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()), e);
        } catch (Exception e) {
            logger.error("调用多模态向量化 API 失败: {}", e.getMessage(), e);
            throw new RuntimeException("多模态向量生成失败: " + e.getMessage(), e);
        }
    }

    private String callApiOnce(WebClient client, List<EmbeddingInput> batch) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", batch.stream().map(EmbeddingInput::toRequestItem).toList());
        if (dimension != null) {
            requestBody.put("dimensions", dimension);
        }
        requestBody.put("encoding_format", "float");

        return client.post()
                .uri("/embeddings")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(2, Duration.ofSeconds(2))
                        .filter(this::isRetriableEmbeddingException)
                        .doBeforeRetry(signal -> logger.warn("重试多模态 Embedding API - 尝试: {}, 错误: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .block(Duration.ofSeconds(requestTimeoutSeconds));
    }

    private WebClient buildClient() {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(apiUrl))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(Math.max(16, maxInMemoryMb) * 1024 * 1024));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        return builder.build();
    }

    private boolean isRetriableEmbeddingException(Throwable exception) {
        if (!(exception instanceof WebClientResponseException responseException)) {
            return false;
        }
        int status = responseException.getStatusCode().value();
        if (status == 400 && isLocalSocketExhaustion(responseException)) {
            return true;
        }
        return status == 408 || status == 429 || responseException.getStatusCode().is5xxServerError();
    }

    private boolean isLocalSocketExhaustion(WebClientResponseException responseException) {
        String body = responseException.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return false;
        }
        String lowerBody = body.toLowerCase(Locale.ROOT);
        return lowerBody.contains("only one usage of each socket address")
                || lowerBody.contains("connectex");
    }

    private EmbeddingApiResponse parseEmbeddingResponse(String response, List<String> billableTexts) throws Exception {
        JsonNode jsonNode = objectMapper.readTree(response);
        JsonNode data = jsonNode.get("data");
        if (data == null || !data.isArray()) {
            throw new RuntimeException("多模态 Embedding 响应格式错误: data 字段不存在或不是数组");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.get("embedding");
            if (embedding != null && embedding.isArray()) {
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
        }

        if (!usageQuotaService.isEmbeddingUsageEnabled()) {
            return new EmbeddingApiResponse(vectors, 0);
        }

        JsonNode usage = jsonNode.path("usage");
        int totalTokens = usage.path("total_tokens").asInt(usage.path("input_tokens").asInt(0));
        return new EmbeddingApiResponse(
                vectors,
                totalTokens > 0 ? totalTokens : usageQuotaService.estimateEmbeddingTokens(billableTexts)
        );
    }

    public String currentModelVersion() {
        return "multimodal:" + model + ":" + dimension;
    }

    private record EmbeddingApiResponse(List<float[]> vectors, int totalTokens) {
    }

    public record EmbeddingUsageResult(List<float[]> vectors, int totalTokens, String modelVersion) {
    }

    public record EmbeddingInput(String text, String image) {
        public static EmbeddingInput text(String text) {
            return new EmbeddingInput(text, null);
        }

        public static EmbeddingInput imageWithText(String text, String image) {
            return new EmbeddingInput(text, image);
        }

        private Object toRequestItem() {
            if (image == null || image.isBlank()) {
                return text == null ? "" : text;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            if (text != null && !text.isBlank()) {
                item.put("text", text);
            }
            item.put("image", image);
            return item;
        }

        private String billableText() {
            if (text != null && !text.isBlank()) {
                return text;
            }
            return image == null || image.isBlank() ? "" : "[image]";
        }
    }
}
