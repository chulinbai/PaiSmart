package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.MultiEmbeddingClient;
import com.yizhaoqi.smartpai.entity.MultimodalEsDocument;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.model.KnowledgeMultimodalItem;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.KnowledgeMultimodalItemRepository;
import io.minio.GetObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class MultimodalVectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(MultimodalVectorizationService.class);
    private static final String UPLOAD_BUCKET = "uploads";

    private final MultiEmbeddingClient multiEmbeddingClient;
    private final MultimodalElasticsearchService multimodalElasticsearchService;
    private final KnowledgeMultimodalItemRepository itemRepository;
    private final DocumentVectorRepository documentVectorRepository;
    private final PdfImageExtractionService pdfImageExtractionService;
    private final UploadService uploadService;

    @Value("${multimodal.pdf-image.max-images-per-file:200}")
    private int maxPdfImagesPerFile;

    @Value("${multimodal.pdf-image.context-max-chars:1200}")
    private int pdfImageContextMaxChars;

    public MultimodalVectorizationService(MultiEmbeddingClient multiEmbeddingClient,
                                          MultimodalElasticsearchService multimodalElasticsearchService,
                                          KnowledgeMultimodalItemRepository itemRepository,
                                          DocumentVectorRepository documentVectorRepository,
                                          PdfImageExtractionService pdfImageExtractionService,
                                          UploadService uploadService) {
        this.multiEmbeddingClient = multiEmbeddingClient;
        this.multimodalElasticsearchService = multimodalElasticsearchService;
        this.itemRepository = itemRepository;
        this.documentVectorRepository = documentVectorRepository;
        this.pdfImageExtractionService = pdfImageExtractionService;
        this.uploadService = uploadService;
    }

    @Transactional
    public void deleteByFileMd5(String fileMd5) {
        try {
            multimodalElasticsearchService.deleteByFileMd5(fileMd5);
        } catch (Exception e) {
            logger.warn("清理多模态 ES 文档失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }
        itemRepository.deleteByFileMd5(fileMd5);
    }

    public long countByFileMd5(String fileMd5) {
        return multimodalElasticsearchService.countByFileMd5(fileMd5);
    }

    @Transactional
    public void deleteByFileMd5AndUserId(String md5,String userId) {
        multimodalElasticsearchService.deleteByFileMd5AndUserId(md5,userId);
        itemRepository.deleteByFileMd5AndUserId(md5,userId);
    }

    public MultimodalVectorizationResult indexFile(String fileMd5,
                                                   String fileName,
                                                   String userId,
                                                   String orgTag,
                                                   boolean isPublic,
                                                   String requesterId) {
        deleteByFileMd5AndUserId(fileMd5,userId);

        String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? userId : requesterId;
        if (isSupportedImageFile(fileName)) {
            return indexStandaloneImage(fileMd5, fileName, userId, orgTag, isPublic, normalizedRequesterId);
        }

        MultimodalVectorizationResult textResult = indexTextChunks(fileMd5, fileName, userId, orgTag, isPublic, normalizedRequesterId);
        if (isPdfFile(fileName)) {
            MultimodalVectorizationResult imageResult = indexPdfImages(fileMd5, fileName, userId, orgTag, isPublic, normalizedRequesterId);
            return textResult.plus(imageResult);
        }
        return textResult;
    }

    private MultimodalVectorizationResult indexTextChunks(String fileMd5,
                                                          String fileName,
                                                          String userId,
                                                          String orgTag,
                                                          boolean isPublic,
                                                          String requesterId) {
        List<DocumentVector> chunks = documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5).stream()
                .filter(chunk -> chunk.getTextContent() != null && !chunk.getTextContent().isBlank())
                .toList();
        if (chunks.isEmpty()) {
            logger.info("多模态文本索引跳过，无可用文本切片: fileMd5={}", fileMd5);
            return MultimodalVectorizationResult.empty(multiEmbeddingClient.currentModelVersion());
        }

        List<MultiEmbeddingClient.EmbeddingInput> inputs = chunks.stream()
                .map(chunk -> MultiEmbeddingClient.EmbeddingInput.text(chunk.getTextContent()))
                .toList();
        MultiEmbeddingClient.EmbeddingUsageResult embeddingResult = multiEmbeddingClient.embedWithUsage(
                inputs,
                requesterId,
                MultiEmbeddingClient.UsageType.UPLOAD
        );

        List<KnowledgeMultimodalItem> items = new ArrayList<>(chunks.size());
        List<MultimodalEsDocument> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentVector chunk = chunks.get(i);
            String itemId = userId+":"+buildTextItemId(fileMd5, chunk.getChunkId());
            KnowledgeMultimodalItem item = baseItem(itemId, fileMd5, fileName, KnowledgeMultimodalItem.TYPE_TEXT_CHUNK,
                    chunk.getTextContent(), embeddingResult.modelVersion(), userId, orgTag, isPublic);
            item.setChunkId(chunk.getChunkId());
            item.setPageNumber(chunk.getPageNumber());
            item.setAnchorText(chunk.getAnchorText());
            item.setTextContent(chunk.getTextContent());
            items.add(item);
            documents.add(toEsDocument(item, embeddingResult.vectors().get(i)));
        }

        itemRepository.saveAll(items);
        multimodalElasticsearchService.bulkIndex(documents);
        logger.info("多模态文本索引完成: fileMd5={}, chunks={}", fileMd5, items.size());
        return new MultimodalVectorizationResult(embeddingResult.totalTokens(), items.size(), embeddingResult.modelVersion());
    }

    private MultimodalVectorizationResult indexPdfImages(String fileMd5,
                                                         String fileName,
                                                         String userId,
                                                         String orgTag,
                                                         boolean isPublic,
                                                         String requesterId) {
        try (GetObjectResponse pdfStream = uploadService.getMergedFileStream(fileMd5)) {
            List<PdfImageExtractionService.PdfEmbeddedImage> images = pdfImageExtractionService.extractImages(
                    pdfStream,
                    Math.max(1, maxPdfImagesPerFile)
            );
            if (images.isEmpty()) {
                logger.info("PDF 未提取到内嵌图片: fileMd5={}", fileMd5);
                return MultimodalVectorizationResult.empty(multiEmbeddingClient.currentModelVersion());
            }

            Map<Integer, String> pageTextMap = buildPageTextMap(fileMd5);
            List<MultiEmbeddingClient.EmbeddingInput> inputs = new ArrayList<>(images.size());
            List<KnowledgeMultimodalItem> items = new ArrayList<>(images.size());
            for (PdfImageExtractionService.PdfEmbeddedImage image : images) {
                String imageText = buildPdfImageText(fileName, image.pageNumber(), image.imageSequence(), pageTextMap.get(image.pageNumber()));
                String embeddedImageId = buildEmbeddedImageId(fileMd5, image.pageNumber(), image.imageSequence());
                String itemId = userId+":"+buildDocumentImageItemId(fileMd5, image.pageNumber(), image.imageSequence());
                KnowledgeMultimodalItem item = baseItem(itemId, fileMd5, fileName, KnowledgeMultimodalItem.TYPE_DOCUMENT_IMAGE,
                        imageText, null, userId, orgTag, isPublic);
                item.setPageNumber(image.pageNumber());
                item.setImageSequence(image.imageSequence());
                item.setEmbeddedImageId(embeddedImageId);
                item.setImageSourceType(KnowledgeMultimodalItem.SOURCE_DOCUMENT_EMBEDDED);
                item.setImageText(imageText);
                item.setContentType(image.contentType());
                item.setWidth(image.width());
                item.setHeight(image.height());
                items.add(item);
                inputs.add(MultiEmbeddingClient.EmbeddingInput.imageWithText(imageText, image.toDataUrl()));
            }

            MultiEmbeddingClient.EmbeddingUsageResult embeddingResult = multiEmbeddingClient.embedWithUsage(
                    inputs,
                    requesterId,
                    MultiEmbeddingClient.UsageType.UPLOAD
            );

            List<MultimodalEsDocument> documents = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                KnowledgeMultimodalItem item = items.get(i);
                item.setModelVersion(embeddingResult.modelVersion());
                documents.add(toEsDocument(item, embeddingResult.vectors().get(i)));
            }

            itemRepository.saveAll(items);
            multimodalElasticsearchService.bulkIndex(documents);
            logger.info("PDF 内嵌图片多模态索引完成: fileMd5={}, images={}", fileMd5, items.size());
            return new MultimodalVectorizationResult(embeddingResult.totalTokens(), items.size(), embeddingResult.modelVersion());
        } catch (Exception e) {
            throw new RuntimeException("PDF 内嵌图片多模态索引失败: " + e.getMessage(), e);
        }
    }

    private MultimodalVectorizationResult indexStandaloneImage(String fileMd5,
                                                               String fileName,
                                                               String userId,
                                                               String orgTag,
                                                               boolean isPublic,
                                                               String requesterId) {
        try {
            String imageUrl = uploadService.transToPublicUrl(uploadService.generateMergedObjectUrl(fileMd5));
            String imageText = buildStandaloneImageText(fileName);
            ImageDimensions dimensions = readImageDimensions(fileMd5);
            KnowledgeMultimodalItem item = baseItem(userId+":"+buildStandaloneImageItemId(fileMd5), fileMd5, fileName,
                    KnowledgeMultimodalItem.TYPE_STANDALONE_IMAGE, imageText, null, userId, orgTag, isPublic);
            item.setImageText(imageText);
            item.setImageSourceType(KnowledgeMultimodalItem.SOURCE_MINIO_OBJECT);
            item.setImageUrl(imageUrl);
            item.setBucketName(UPLOAD_BUCKET);
            item.setObjectName("merged/" + fileMd5);
            item.setContentType(contentTypeFromFileName(fileName));
            item.setWidth(dimensions.width());
            item.setHeight(dimensions.height());

            MultiEmbeddingClient.EmbeddingUsageResult embeddingResult = multiEmbeddingClient.embedWithUsage(
                    List.of(MultiEmbeddingClient.EmbeddingInput.imageWithText(imageText, imageUrl)),
                    requesterId,
                    MultiEmbeddingClient.UsageType.UPLOAD
            );
            item.setModelVersion(embeddingResult.modelVersion());

            itemRepository.save(item);
            multimodalElasticsearchService.bulkIndex(List.of(toEsDocument(item, embeddingResult.vectors().get(0))));
            logger.info("独立图片多模态索引完成: fileMd5={}, fileName={}", fileMd5, fileName);
            return new MultimodalVectorizationResult(embeddingResult.totalTokens(), 1, embeddingResult.modelVersion());
        } catch (Exception e) {
            throw new RuntimeException("独立图片多模态索引失败: " + e.getMessage(), e);
        }
    }

    private KnowledgeMultimodalItem baseItem(String itemId,
                                             String fileMd5,
                                             String fileName,
                                             String knowledgeType,
                                             String searchText,
                                             String modelVersion,
                                             String userId,
                                             String orgTag,
                                             boolean isPublic) {
        KnowledgeMultimodalItem item = new KnowledgeMultimodalItem();
        item.setItemId(itemId);
        item.setFileMd5(fileMd5);
        item.setFileName(fileName);
        item.setKnowledgeType(knowledgeType);
        item.setSearchText(searchText);
        item.setModelVersion(modelVersion);
        item.setUserId(userId);
        item.setOrgTag(orgTag);
        item.setPublic(isPublic);
        return item;
    }

    private MultimodalEsDocument toEsDocument(KnowledgeMultimodalItem item, float[] vector) {
        MultimodalEsDocument doc = new MultimodalEsDocument();
        doc.setId(item.getItemId());
        doc.setItemId(item.getItemId());
        doc.setFileMd5(item.getFileMd5());
        doc.setFileName(item.getFileName());
        doc.setKnowledgeType(item.getKnowledgeType());
        doc.setSearchText(item.getSearchText());
        doc.setVector(vector);
        doc.setModelVersion(item.getModelVersion());
        doc.setUserId(item.getUserId());
        doc.setOrgTag(item.getOrgTag());
        doc.setPublic(item.isPublic());
        doc.setChunkId(item.getChunkId());
        doc.setPageNumber(item.getPageNumber());
        doc.setAnchorText(item.getAnchorText());
        doc.setTextContent(item.getTextContent());
        doc.setImageText(item.getImageText());
        doc.setImageSourceType(item.getImageSourceType());
        doc.setImageUrl(item.getImageUrl());
        doc.setBucketName(item.getBucketName());
        doc.setObjectName(item.getObjectName());
        doc.setEmbeddedImageId(item.getEmbeddedImageId());
        doc.setImageSequence(item.getImageSequence());
        doc.setContentType(item.getContentType());
        doc.setWidth(item.getWidth());
        doc.setHeight(item.getHeight());
        return doc;
    }

    private Map<Integer, String> buildPageTextMap(String fileMd5) {
        Map<Integer, StringBuilder> builders = new LinkedHashMap<>();
        documentVectorRepository.findByFileMd5OrderByChunkIdAsc(fileMd5).stream()
                .filter(chunk -> chunk.getPageNumber() != null)
                .sorted(Comparator.comparing(DocumentVector::getChunkId))
                .forEach(chunk -> builders
                        .computeIfAbsent(chunk.getPageNumber(), ignored -> new StringBuilder())
                        .append(chunk.getTextContent() == null ? "" : chunk.getTextContent())
                        .append("\n\n"));

        Map<Integer, String> result = new LinkedHashMap<>();
        builders.forEach((page, builder) -> result.put(page, limitText(builder.toString(), pdfImageContextMaxChars)));
        return result;
    }

    private ImageDimensions readImageDimensions(String fileMd5) {
        try (InputStream inputStream = uploadService.getMergedFileStream(fileMd5)) {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                return new ImageDimensions(null, null);
            }
            return new ImageDimensions(image.getWidth(), image.getHeight());
        } catch (Exception e) {
            logger.warn("读取独立图片尺寸失败: fileMd5={}, error={}", fileMd5, e.getMessage());
            return new ImageDimensions(null, null);
        }
    }

    private String buildPdfImageText(String fileName, int pageNumber, int imageSequence, String pageText) {
        StringBuilder text = new StringBuilder();
        text.append(fileName).append(" 第").append(pageNumber).append("页第").append(imageSequence).append("张图片");
        String limitedPageText = limitText(pageText, pdfImageContextMaxChars);
        if (limitedPageText != null && !limitedPageText.isBlank()) {
            text.append("\n\n所在页文字：").append(limitedPageText);
        }
        return text.toString();
    }

    private String buildStandaloneImageText(String fileName) {
        return "独立图片：" + (fileName == null || fileName.isBlank() ? "未命名图片" : fileName);
    }

    private String buildTextItemId(String fileMd5, Integer chunkId) {
        return fileMd5 + ":text:" + chunkId;
    }

    private String buildDocumentImageItemId(String fileMd5, int pageNumber, int imageSequence) {
        return fileMd5 + ":pdf-image:" + pageNumber + ":" + imageSequence;
    }

    private String buildEmbeddedImageId(String fileMd5, int pageNumber, int imageSequence) {
        return fileMd5 + ":p" + pageNumber + ":img" + imageSequence;
    }

    private String buildStandaloneImageItemId(String fileMd5) {
        return fileMd5 + ":image:standalone";
    }

    private boolean isPdfFile(String fileName) {
        return "pdf".equalsIgnoreCase(extension(fileName));
    }

    public boolean isSupportedImageFile(String fileName) {
        return switch (extension(fileName)) {
            case "jpg", "jpeg", "png", "gif", "bmp", "webp", "tif", "tiff" -> true;
            default -> false;
        };
    }

    private String contentTypeFromFileName(String fileName) {
        return switch (extension(fileName)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private String extension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String limitText(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (maxChars <= 0 || normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...";
    }

    private record ImageDimensions(Integer width, Integer height) {
    }

    public record MultimodalVectorizationResult(int actualEmbeddingTokens, int actualItemCount, String modelVersion) {
        private static MultimodalVectorizationResult empty(String modelVersion) {
            return new MultimodalVectorizationResult(0, 0, modelVersion);
        }

        private MultimodalVectorizationResult plus(MultimodalVectorizationResult other) {
            if (other == null) {
                return this;
            }
            String mergedModelVersion = other.modelVersion() != null ? other.modelVersion() : modelVersion;
            return new MultimodalVectorizationResult(
                    actualEmbeddingTokens + other.actualEmbeddingTokens(),
                    actualItemCount + other.actualItemCount(),
                    mergedModelVersion
            );
        }
    }
}
