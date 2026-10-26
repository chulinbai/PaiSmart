package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import io.minio.GetObjectResponse;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.KnowledgeMultimodalItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.PdfImageExtractionService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import reactor.core.Disposable;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private final UsageQuotaService usageQuotaService;
    private final ModelProviderConfigService modelProviderConfigService;
    private final UploadService uploadService;
    private final PdfImageExtractionService pdfImageExtractionService;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    @Value("${llm.multimodal.max-images-per-answer:4}")
    private int maxImagesPerAnswer;
    
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties,
                         UsageQuotaService usageQuotaService,
                         ModelProviderConfigService modelProviderConfigService,
                         UploadService uploadService,
                         PdfImageExtractionService pdfImageExtractionService) {
        String normalizedApiUrl = ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(apiUrl);
        WebClient.Builder builder = WebClient.builder().baseUrl(normalizedApiUrl);
        
        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        
        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
        this.usageQuotaService = usageQuotaService;
        this.modelProviderConfigService = modelProviderConfigService;
        this.uploadService = uploadService;
        this.pdfImageExtractionService = pdfImageExtractionService;
        this.objectMapper = new ObjectMapper();
    }
    
    public void streamResponse(String requesterId,
                             String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError) {
        
        Map<String, Object> request = buildRequest(userMessage, context, history);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
        
        try {
            webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                        chunk -> processChunk(chunk, usageTracker, onChunk),
                        error -> {
                            settleUsage(usageTracker);
                            onError.accept(error);
                        },
                        () -> settleUsage(usageTracker)
                    );
        } catch (Exception e) {
            usageQuotaService.abortReservation(reservation);
            throw e;
        }
    }

    public void streamResponseWithImages(String requesterId,
                                         String userMessage,
                                         String context,
                                         List<Map<String, String>> history,
                                         List<SearchResult> searchResults,
                                         Consumer<String> onChunk,
                                         Consumer<Throwable> onError) {
        List<Map<String, Object>> imageContent = buildImageQuestionContent(userMessage, searchResults);
        if (imageContent.isEmpty()) {
            fallbackToTextResponse(requesterId, userMessage, context, history, onChunk, onError);
            return;
        }

        Map<String, Object> request = buildImageAnswerRequest(userMessage, context, history, imageContent);
        int estimatedPromptTokens = estimateMultimodalPromptTokens(userMessage, context, history, imageContent);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 2000;
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
        AtomicBoolean emitted = new AtomicBoolean(false);
        Consumer<String> guardedChunkConsumer = chunk -> {
            emitted.set(true);
            onChunk.accept(chunk);
        };

        try {
            webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    .subscribe(
                            chunk -> processChunk(chunk, usageTracker, guardedChunkConsumer),
                            error -> {
                                if (!emitted.get()) {
                                    usageTracker.settled = true;
                                    usageQuotaService.abortReservation(reservation);
                                    logger.warn("图文问答调用失败，降级为纯文本上下文: {}", error.getMessage(), error);
                                    fallbackToTextResponse(requesterId, userMessage, context, history, onChunk, onError);
                                    return;
                                }
                                settleUsage(usageTracker);
                                onError.accept(error);
                            },
                            () -> settleUsage(usageTracker)
                    );
        } catch (Exception e) {
            usageQuotaService.abortReservation(reservation);
            logger.warn("图文问答请求构建或发送失败，降级为纯文本上下文: {}", e.getMessage(), e);
            fallbackToTextResponse(requesterId, userMessage, context, history, onChunk, onError);
        }
    }

    private void fallbackToTextResponse(String requesterId,
                                        String userMessage,
                                        String context,
                                        List<Map<String, String>> history,
                                        Consumer<String> onChunk,
                                        Consumer<Throwable> onError) {
        try {
            streamResponse(requesterId, userMessage, context, history, onChunk, onError);
        } catch (Exception fallbackException) {
            onError.accept(fallbackException);
        }
    }

    public boolean hasImageResults(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return false;
        }
        return searchResults.stream().anyMatch(this::isImageResult);
    }

    public List<Map<String, Object>> buildImageQuestionContent(String userMessage, List<SearchResult> searchResults) {
        List<ImageEvidence> evidences = resolveImageEvidences(searchResults);
        if (evidences.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(textContentBlock("用户问题：\n" + nullToEmpty(userMessage)
                + "\n\n下面是从知识库检索到的图片证据。请结合图片本身、图片上下文和已有 tool 文本结果回答；"
                + "如果图片不可读或模型不支持图片，以 tool 文本结果中的图片上下文为准。"));
        for (int i = 0; i < evidences.size(); i++) {
            ImageEvidence evidence = evidences.get(i);
            content.add(textContentBlock("图片证据 #" + (i + 1) + "\n" + evidence.description()));
            content.add(imageUrlContentBlock(evidence.imageUrl()));
        }
        return content;
    }

    public List<Map<String, Object>> buildToolResultContent(String toolText, List<SearchResult> searchResults) {
        List<ImageEvidence> evidences = resolveImageEvidences(searchResults);
        if (evidences.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(textContentBlock(nullToEmpty(toolText)
                + "\n\n以下图片是 search_knowledge 工具命中的图片证据。请把这些图片视为本次 tool_result 的一部分，"
                + "结合上面的检索文本与图片内容回答；如果图片不可读，以检索文本中的图片上下文为准。"));
        for (int i = 0; i < evidences.size(); i++) {
            ImageEvidence evidence = evidences.get(i);
            content.add(textContentBlock("图片证据 #" + (i + 1) + "\n" + evidence.description()));
            content.add(imageUrlContentBlock(evidence.imageUrl()));
        }
        return content;
    }

    public String summarize(String requesterId, String topic, List<SearchResult> searchResults) {
        return summarize(requesterId, topic, searchResults, null);
    }

    public String summarize(String requesterId,
                            String topic,
                            List<SearchResult> searchResults,
                            Consumer<String> onChunk) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("摘要主题不能为空");
        }
        if (searchResults == null || searchResults.isEmpty()) {
            String noResult = "未检索到与主题相关的知识库文档，无法生成基于知识库的摘要。";
            if (onChunk != null) {
                onChunk.accept(noResult);
            }
            return noResult;
        }

        List<Map<String, String>> messages = buildSummaryMessages(topic, searchResults);
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens()
                : 1600;
        UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = null;

        try {
            SummaryEndpoint summaryEndpoint = resolveSummaryEndpoint();
            Map<String, Object> request = new HashMap<>();
            request.put("model", summaryEndpoint.model());
            request.put("messages", messages);
            request.put("stream", true);
            request.put("stream_options", Map.of("include_usage", true));
            request.put("max_tokens", maxCompletionTokens);
            AiProperties.Generation gen = aiProperties.getGeneration();
            if (gen.getTemperature() != null) {
                request.put("temperature", Math.min(gen.getTemperature(), 0.3d));
            } else {
                request.put("temperature", 0.2d);
            }
            if (gen.getTopP() != null) {
                request.put("top_p", gen.getTopP());
            }

            usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
            String summary = executeSummaryStream(summaryEndpoint, request, usageTracker, onChunk);
            logger.info("generate_summary 内部摘要完成: provider={}, model={}, promptTokensEstimate={}, summaryChars={}",
                    summaryEndpoint.provider(), summaryEndpoint.model(), estimatedPromptTokens, summary.length());
            return summary;
        } catch (Exception e) {
            if (usageTracker == null || !usageTracker.settled) {
                usageQuotaService.abortReservation(reservation);
            }
            throw new RuntimeException("生成知识库摘要失败", e);
        }
    }

    private String executeSummaryStream(SummaryEndpoint summaryEndpoint,
                                        Map<String, Object> request,
                                        StreamUsageTracker usageTracker,
                                        Consumer<String> onChunk) {
        CompletableFuture<String> summaryFuture = new CompletableFuture<>();
        long startedAt = System.currentTimeMillis();
        AtomicBoolean firstChunkLogged = new AtomicBoolean(false);
        AtomicInteger chunkCount = new AtomicInteger(0);
        Consumer<String> chunkConsumer = chunk -> {
            if (chunk != null && !chunk.isEmpty()) {
                int currentChunkCount = chunkCount.incrementAndGet();
                if (firstChunkLogged.compareAndSet(false, true)) {
                    logger.info("generate_summary 内部摘要收到首个流式 chunk: provider={}, model={}, elapsedMs={}, chunkChars={}",
                            summaryEndpoint.provider(), summaryEndpoint.model(), System.currentTimeMillis() - startedAt, chunk.length());
                }
                logger.debug("generate_summary 内部摘要流式 chunk: provider={}, model={}, chunkIndex={}, chunkChars={}",
                        summaryEndpoint.provider(), summaryEndpoint.model(), currentChunkCount, chunk.length());
            }
            if (onChunk != null) {
                onChunk.accept(chunk);
            }
        };

        Disposable subscription = summaryEndpoint.webClient().post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> processChunk(chunk, usageTracker, chunkConsumer),
                        error -> {
                            settleUsage(usageTracker);
                            summaryFuture.completeExceptionally(error);
                        },
                        () -> {
                            settleUsage(usageTracker);
                            logger.info("generate_summary 内部摘要流结束: provider={}, model={}, elapsedMs={}, chunks={}, summaryChars={}",
                                    summaryEndpoint.provider(),
                                    summaryEndpoint.model(),
                                    System.currentTimeMillis() - startedAt,
                                    chunkCount.get(),
                                    usageTracker.responseContent.length());
                            summaryFuture.complete(usageTracker.responseContent.toString().trim());
                        }
                );

        try {
            String summary = summaryFuture.get(90, TimeUnit.SECONDS);
            if (summary == null || summary.isBlank()) {
                throw new IllegalStateException("DeepSeek 摘要流未返回有效内容");
            }
            return summary;
        } catch (TimeoutException e) {
            subscription.dispose();
            settleUsage(usageTracker);
            throw new RuntimeException("DeepSeek 摘要流式响应超时", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            subscription.dispose();
            settleUsage(usageTracker);
            throw new RuntimeException("DeepSeek 摘要流式响应被中断", e);
        } catch (ExecutionException e) {
            subscription.dispose();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("DeepSeek 摘要流式响应失败", cause);
        }
    }

    private SummaryEndpoint resolveSummaryEndpoint() {
        try {
            ModelProviderConfigService.ActiveProviderView provider =
                    modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_LLM);
            WebClient.Builder builder = WebClient.builder()
                    .baseUrl(ModelProviderConfigService.normalizeOpenAiCompatibleBaseUrl(provider.apiBaseUrl()));
            if (provider.apiKey() != null && !provider.apiKey().isBlank()) {
                builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
            }
            return new SummaryEndpoint(builder.build(), provider.model(), provider.provider());
        } catch (Exception exception) {
            logger.warn("无法读取活动 LLM Provider，generate_summary 内部摘要回退到 deepseek.* 配置: {}", exception.getMessage());
            return new SummaryEndpoint(webClient, model, "deepseek");
        }
    }

    private Map<String, Object> buildImageAnswerRequest(String userMessage,
                                                        String context,
                                                        List<Map<String, String>> history,
                                                        List<Map<String, Object>> imageContent) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", buildImageMessages(userMessage, context, history, imageContent));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, Object>> buildImageMessages(String userMessage,
                                                         String context,
                                                         List<Map<String, String>> history,
                                                         List<Map<String, Object>> imageContent) {
        List<Map<String, String>> textMessages = buildMessages(userMessage, context, history);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (int i = 0; i < textMessages.size(); i++) {
            Map<String, String> textMessage = textMessages.get(i);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", textMessage.get("role"));
            if (i == textMessages.size() - 1 && "user".equals(textMessage.get("role"))) {
                message.put("content", imageContent);
            } else {
                message.put("content", textMessage.get("content"));
            }
            messages.add(message);
        }
        return messages;
    }

    private int estimateMultimodalPromptTokens(String userMessage,
                                               String context,
                                               List<Map<String, String>> history,
                                               List<Map<String, Object>> imageContent) {
        int tokens = usageQuotaService.estimateTextTokens(nullToEmpty(userMessage))
                + usageQuotaService.estimateTextTokens(nullToEmpty(context));
        if (history != null) {
            for (Map<String, String> message : history) {
                tokens += 8;
                tokens += usageQuotaService.estimateTextTokens(nullToEmpty(message.get("role")));
                tokens += usageQuotaService.estimateTextTokens(nullToEmpty(message.get("content")));
            }
        }
        for (Map<String, Object> block : imageContent) {
            String type = String.valueOf(block.getOrDefault("type", ""));
            if ("text".equals(type)) {
                tokens += usageQuotaService.estimateTextTokens(String.valueOf(block.getOrDefault("text", "")));
            } else if ("image_url".equals(type)) {
                tokens += 256;
            }
        }
        return Math.max(tokens, 1);
    }

    private List<ImageEvidence> resolveImageEvidences(List<SearchResult> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return List.of();
        }

        List<ImageEvidence> evidences = new ArrayList<>();
        int limit = Math.max(1, maxImagesPerAnswer);
        for (SearchResult result : searchResults) {
            if (evidences.size() >= limit) {
                break;
            }
            if (!isImageResult(result)) {
                continue;
            }
            try {
                String imageUrl = resolveEvidenceImageUrl(result);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                evidences.add(new ImageEvidence(imageUrl, buildImageEvidenceDescription(result)));
            } catch (Exception exception) {
                logger.warn("检索图片证据解析失败，将仅使用文字上下文: fileMd5={}, itemId={}, page={}, image={}, error={}",
                        result.getFileMd5(),
                        result.getItemId(),
                        result.getPageNumber(),
                        result.getImageSequence(),
                        exception.getMessage());
            }
        }
        return evidences;
    }

    private boolean isImageResult(SearchResult result) {
        if (result == null) {
            return false;
        }
        String knowledgeType = result.getKnowledgeType();
        return KnowledgeMultimodalItem.TYPE_DOCUMENT_IMAGE.equals(knowledgeType)
                || KnowledgeMultimodalItem.TYPE_STANDALONE_IMAGE.equals(knowledgeType);
    }

    private String resolveEvidenceImageUrl(SearchResult result) throws Exception {
        if (KnowledgeMultimodalItem.TYPE_STANDALONE_IMAGE.equals(result.getKnowledgeType())) {
            String contentType = normalizeImageContentType(result.getContentType());
            if (isLikelyPublicImageUrl(result.getImageUrl())
                    && (contentType.isBlank() || isDeepSeekSupportedImageType(contentType))) {
                return result.getImageUrl();
            }
            if (result.getFileMd5() == null || result.getFileMd5().isBlank()) {
                return contentType.isBlank() || isDeepSeekSupportedImageType(contentType) ? result.getImageUrl() : null;
            }
            try (GetObjectResponse imageStream = uploadService.getMergedFileStream(result.getFileMd5())) {
                return toDeepSeekCompatibleDataUrl(imageStream.readAllBytes(), contentType);
            }
        }
        if (KnowledgeMultimodalItem.TYPE_DOCUMENT_IMAGE.equals(result.getKnowledgeType())) {
            if (result.getFileMd5() == null || result.getPageNumber() == null || result.getImageSequence() == null) {
                return null;
            }
            try (GetObjectResponse pdfStream = uploadService.getMergedFileStream(result.getFileMd5())) {
                PdfImageExtractionService.PdfEmbeddedImage image = pdfImageExtractionService.extractOne(
                        pdfStream,
                        result.getPageNumber(),
                        result.getImageSequence()
                );
                return image.toDataUrl();
            }
        }
        return null;
    }

    private String toDeepSeekCompatibleDataUrl(byte[] imageBytes, String contentType) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            return null;
        }
        String normalizedContentType = normalizeImageContentType(contentType);
        if (isDeepSeekSupportedImageType(normalizedContentType)) {
            return "data:" + normalizedContentType + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);
        }

        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
        if (image == null) {
            return null;
        }
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(outputStream.toByteArray());
        }
    }

    private boolean isDeepSeekSupportedImageType(String contentType) {
        return switch (normalizeImageContentType(contentType)) {
            case "image/jpeg", "image/png", "image/gif", "image/webp" -> true;
            default -> false;
        };
    }

    private String normalizeImageContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        String normalized = contentType.trim().toLowerCase();
        if ("image/jpg".equals(normalized)) {
            return "image/jpeg";
        }
        return normalized;
    }

    private boolean isLikelyPublicImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return false;
        }
        String normalized = imageUrl.toLowerCase();
        if (normalized.startsWith("data:image/")) {
            return true;
        }
        return (normalized.startsWith("http://") || normalized.startsWith("https://"))
                && !normalized.contains("localhost")
                && !normalized.contains("127.0.0.1")
                && !normalized.contains("[::1]");
    }

    private String buildImageEvidenceDescription(SearchResult result) {
        StringBuilder description = new StringBuilder();
        description.append("来源：")
                .append(nullToDash(result.getFileName()))
                .append("，类型：")
                .append(nullToDash(result.getKnowledgeType()));
        if (result.getPageNumber() != null) {
            description.append("，页码：").append(result.getPageNumber());
        }
        if (result.getImageSequence() != null) {
            description.append("，页内图片序号：").append(result.getImageSequence());
        }
        if (result.getEmbeddedImageId() != null && !result.getEmbeddedImageId().isBlank()) {
            description.append("，embeddedImageId：").append(result.getEmbeddedImageId());
        }
        String imageText = firstNonBlank(result.getImageText(), result.getSearchText(), result.getMatchedChunkText());
        if (imageText != null && !imageText.isBlank()) {
            description.append("\n图片文字上下文：").append(limitText(imageText, 1000));
        }
        return description.toString();
    }

    private Map<String, Object> textContentBlock(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text == null ? "" : text);
        return block;
    }

    private Map<String, Object> imageUrlContentBlock(String imageUrl) {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("url", imageUrl);
        image.put("detail", "auto");

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "image_url");
        block.put("image_url", image);
        return block;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
    
    private Map<String, Object> buildRequest(String userMessage, 
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}", 
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildSummaryMessages(String topic, List<SearchResult> searchResults) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "你是 generate_summary 工具内部使用的知识库摘要模型。"
                        + "只基于提供的知识库片段生成结构化摘要，不要发起工具调用，不要把自己当作外层 ReAct 循环。"
                        + "输出应包含：核心结论、关键依据、可执行建议或待确认问题。"
        ));
        messages.add(Map.of(
                "role", "user",
                "content", "主题：" + topic + "\n\n知识库片段：\n" + buildSummaryContext(searchResults)
        ));
        return messages;
    }

    private String buildSummaryContext(List<SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < searchResults.size(); i++) {
            SearchResult result = searchResults.get(i);
            context.append("[").append(i + 1).append("] ");
            if (result.getFileName() != null && !result.getFileName().isBlank()) {
                context.append("文件：").append(result.getFileName()).append("，");
            }
            context.append("fileMd5=").append(result.getFileMd5())
                    .append("，chunkId=").append(result.getChunkId());
            if (result.getPageNumber() != null) {
                context.append("，page=").append(result.getPageNumber());
            }
            context.append("\n")
                    .append(limitText(result.getMatchedChunkText() != null ? result.getMatchedChunkText() : result.getTextContent(), 1800))
                    .append("\n\n");
        }
        return context.toString();
    }

    private String extractSummaryContent(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("DeepSeek 摘要响应为空");
        }
        JsonNode node = objectMapper.readTree(responseBody);
        String summary = node.path("choices")
                .path(0)
                .path("message")
                .path("content")
                .asText("")
                .trim();
        if (summary.isEmpty()) {
            throw new IllegalStateException("DeepSeek 摘要响应未包含 message.content");
        }
        return summary;
    }

    private void settleSummaryUsage(UsageQuotaService.TokenReservation reservation,
                                    String responseBody,
                                    int estimatedPromptTokens,
                                    String summary) {
        try {
            JsonNode usageNode = objectMapper.readTree(responseBody).path("usage");
            int promptTokens = usageNode.path("prompt_tokens").asInt(estimatedPromptTokens);
            int completionTokens = usageNode.path("completion_tokens").asInt(usageQuotaService.estimateTextTokens(summary));
            usageQuotaService.settleReservation(reservation, promptTokens + completionTokens);
        } catch (Exception e) {
            int actualTokens = estimatedPromptTokens + usageQuotaService.estimateTextTokens(summary);
            usageQuotaService.settleReservation(reservation, actualTokens);
        }
    }

    private String limitText(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private record SummaryEndpoint(WebClient webClient, String model, String provider) {
    }

    private record ImageEvidence(String imageUrl, String description) {
    }
    
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    private void processChunk(String rawChunk, StreamUsageTracker usageTracker, Consumer<String> onChunk) {
        try {
            for (String chunk : extractPayloads(rawChunk)) {
                if ("[DONE]".equals(chunk)) {
                    logger.debug("对话结束");
                    continue;
                }

                JsonNode node = objectMapper.readTree(chunk);
                JsonNode usageNode = node.path("usage");
                if (usageNode.isObject()) {
                    usageTracker.promptTokens = usageNode.path("prompt_tokens").asInt(usageTracker.promptTokens);
                    usageTracker.completionTokens = usageNode.path("completion_tokens").asInt(usageTracker.completionTokens);
                }

                String content = node.path("choices")
                        .path(0)
                        .path("delta")
                        .path("content")
                        .asText("");

                if (!content.isEmpty()) {
                    usageTracker.responseContent.append(content);
                    onChunk.accept(content);
                }
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    private List<String> extractPayloads(String rawChunk) {
        List<String> payloads = new ArrayList<>();
        if (rawChunk == null || rawChunk.isBlank()) {
            return payloads;
        }

        String trimmed = rawChunk.trim();
        for (String line : trimmed.split("\\r?\\n")) {
            String payload = line.trim();
            if (payload.isEmpty() || payload.startsWith(":")) {
                continue;
            }
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (!payload.isEmpty()) {
                payloads.add(payload);
            }
        }

        if (payloads.isEmpty()) {
            payloads.add(trimmed);
        }
        return payloads;
    }

    private void settleUsage(StreamUsageTracker usageTracker) {
        if (usageTracker == null || usageTracker.settled) {
            return;
        }

        usageTracker.settled = true;
        int actualPromptTokens = usageTracker.promptTokens > 0
                ? usageTracker.promptTokens
                : usageTracker.estimatedPromptTokens;
        int actualCompletionTokens = usageTracker.completionTokens > 0
                ? usageTracker.completionTokens
                : usageQuotaService.estimateTextTokens(usageTracker.responseContent.toString());

        usageQuotaService.settleReservation(usageTracker.reservation, actualPromptTokens + actualCompletionTokens);
    }

    private static final class StreamUsageTracker {
        private final UsageQuotaService.TokenReservation reservation;
        private final int estimatedPromptTokens;
        private final StringBuilder responseContent = new StringBuilder();
        private volatile int promptTokens;
        private volatile int completionTokens;
        private volatile boolean settled;

        private StreamUsageTracker(UsageQuotaService.TokenReservation reservation, int estimatedPromptTokens) {
            this.reservation = reservation;
            this.estimatedPromptTokens = estimatedPromptTokens;
        }
    }
}
