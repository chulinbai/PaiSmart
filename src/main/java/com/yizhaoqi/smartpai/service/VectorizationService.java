package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.TextChunk;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            List<TextChunk> rawChunks = fetchTextChunks(fileMd5);
            if (rawChunks == null || rawChunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            List<TextChunk> chunks = rawChunks.stream()
                    .filter(chunk -> chunk.getContent() != null && !chunk.getContent().isBlank())
                    .toList();
            int skippedChunks = rawChunks.size() - chunks.size();
            if (skippedChunks > 0) {
                logger.warn("向量化前跳过空白切片: fileMd5={}, skipped={}, total={}",
                        fileMd5, skippedChunks, rawChunks.size());
            }
            chunks = deduplicateChunksByChunkId(chunks, fileMd5);
            if (chunks.isEmpty()) {
                logger.warn("文件没有可向量化的有效文本切片，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }

            // 提取文本内容
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();

            // 调用外部模型生成向量
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();
            if (vectors.size() != chunks.size()) {
                throw new RuntimeException(String.format(
                        "Embedding 返回数量不匹配: fileMd5=%s, chunks=%d, vectors=%d",
                        fileMd5, chunks.size(), vectors.size()
                ));
            }

            // 构建 Elasticsearch 文档并存储
            List<TextChunk> chunksForIndex = chunks;
            List<EsDocument> esDocuments = IntStream.range(0, chunksForIndex.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunksForIndex.get(i).getChunkId(),
                            chunksForIndex.get(i).getContent(),
                            chunksForIndex.get(i).getPageNumber(),
                            chunksForIndex.get(i).getAnchorText(),
                            vectors.get(i),
                            embeddingResult.modelVersion(),
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();

            elasticsearchService.bulkIndex(esDocuments); // 批量存储到 Elasticsearch

            logger.info("向量化完成，fileMd5: {}", fileMd5);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}", fileMd5, e);
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                throw new RuntimeException("向量化失败", e);
            }
            throw new RuntimeException("向量化失败: " + message, e);
        }
    }
    
    private List<TextChunk> deduplicateChunksByChunkId(List<TextChunk> chunks, String fileMd5) {
        Map<Integer, TextChunk> uniqueChunks = new LinkedHashMap<>();
        int duplicateCount = 0;
        for (TextChunk chunk : chunks) {
            TextChunk previous = uniqueChunks.putIfAbsent(chunk.getChunkId(), chunk);
            if (previous != null) {
                duplicateCount++;
            }
        }
        if (duplicateCount > 0) {
            logger.warn("向量化前跳过重复切片: fileMd5={}, duplicate={}, before={}, after={}",
                    fileMd5, duplicateCount, chunks.size(), uniqueChunks.size());
        }
        return new ArrayList<>(uniqueChunks.values());
    }

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getPageNumber(),
                        vector.getAnchorText()
                ))
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
