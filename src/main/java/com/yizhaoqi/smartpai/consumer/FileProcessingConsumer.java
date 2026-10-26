package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.config.KafkaConfig;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.model.FileProcessingTask;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.DocumentService;
import com.yizhaoqi.smartpai.service.ElasticsearchService;
import com.yizhaoqi.smartpai.service.FileTypeValidationService;
import com.yizhaoqi.smartpai.service.MultimodalVectorizationService;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.VectorizationUsageResult;
import io.minio.errors.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Service
@Slf4j
public class FileProcessingConsumer {

    private final ParseService parseService;
    private final DocumentService documentService;
    private final DocumentVectorRepository documentVectorRepository;
    private final FileUploadRepository fileUploadRepository;
    private final ElasticsearchService elasticsearchService;
    private final MultimodalVectorizationService multimodalVectorizationService;
    private final FileTypeValidationService fileTypeValidationService;
    @Autowired
    private KafkaConfig kafkaConfig;
    @Autowired(required=false)
    private com.yizhaoqi.smartpai.service.knowledge.KnowledgeIndexOrchestrator knowledgeIndexes;
    @Autowired
    private com.yizhaoqi.smartpai.service.knowledge.KnowledgeSourceAssembler knowledgeSources;
    @Autowired
    private com.yizhaoqi.smartpai.service.UploadService knowledgeUploads;


    public FileProcessingConsumer(
            ParseService parseService,
            DocumentService documentService,
            DocumentVectorRepository documentVectorRepository,
            FileUploadRepository fileUploadRepository,
            ElasticsearchService elasticsearchService,
            MultimodalVectorizationService multimodalVectorizationService,
            FileTypeValidationService fileTypeValidationService
    ) {
        this.parseService = parseService;
        this.documentService = documentService;
        this.documentVectorRepository = documentVectorRepository;
        this.fileUploadRepository = fileUploadRepository;
        this.elasticsearchService = elasticsearchService;
        this.multimodalVectorizationService = multimodalVectorizationService;
        this.fileTypeValidationService = fileTypeValidationService;
    }

    @KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
    public void processTask(FileProcessingTask task) {
        if(knowledgeIndexes!=null) { processKnowledgeTask(task);return; }
        log.info("Received task: {}", task);
        log.info("文件权限信息: userId={}, orgTag={}, isPublic={}", 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        if(fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(task.getFileMd5(),task.getUserId()).isEmpty()) return;
        documentService.markVectorizationProcessing(task.getFileMd5(),task.getUserId(), false);

        if (FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
            processReindexTask(task);
            return;
        }

        InputStream fileStream = null;
        try {
            // 下载文件
            fileStream = downloadFileFromStorage(task.getFilePath());
            // 在 downloadFileFromStorage 返回后立即检查流是否可读
            if (fileStream == null) {
                throw new IOException("流为空");
            }

            // 强制转换为可缓存流
            if (!fileStream.markSupported()) {
                fileStream = new BufferedInputStream(fileStream);
            }

            // 解析文件
            clearPreviousIndexingData(task.getFileMd5(),task.getUserId());

            VectorizationUsageResult vectorizationResult;
            if (fileTypeValidationService.isSupportedImageFileName(task.getFileName())) {
                MultimodalVectorizationService.MultimodalVectorizationResult multimodalResult =
                        multimodalVectorizationService.indexFile(
                                task.getFileMd5(),
                                task.getFileName(),
                                task.getUserId(),
                                task.getOrgTag(),
                                task.isPublic(),
                                task.getUserId()
                        );
                vectorizationResult = toVectorizationUsageResult(multimodalResult);
                log.info("独立图片多模态入库完成，fileMd5: {}, items: {}",
                        task.getFileMd5(), multimodalResult.actualItemCount());
            } else {
                if(documentVectorRepository.countByFileMd5(task.getFileMd5())==0) parseService.parseAndSave(task.getFileMd5(), fileStream,
                        task.getUserId(), task.getOrgTag(), task.isPublic());
                log.info("文件解析完成，fileMd5: {}", task.getFileMd5());

                MultimodalVectorizationService.MultimodalVectorizationResult multimodalResult =
                        multimodalVectorizationService.indexFile(
                                task.getFileMd5(),
                                task.getFileName(),
                                task.getUserId(),
                                task.getOrgTag(),
                                task.isPublic(),
                                task.getUserId()
                        );
                vectorizationResult = toVectorizationUsageResult(multimodalResult);
            }
            documentService.markVectorizationCompleted(task.getFileMd5(),task.getUserId(), vectorizationResult);
            log.info("向量化完成，fileMd5: {}", task.getFileMd5());
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(),task.getUserId(), e);
            log.error("Error processing task: {}", task, e);
            // 抛出异常让 Kafka 的 DefaultErrorHandler 捕获并触发重试 / 死信
            throw new RuntimeException("Error processing task", e);
        } finally {
            // 确保关闭输入流
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    log.error("Error closing file stream", e);
                }
            }
        }
    }

    private void processKnowledgeTask(FileProcessingTask task) {
        FileUpload file;
        if(task.getDocumentId()!=null) {
            file=fileUploadRepository.findById(task.getDocumentId()).orElse(null);
            if(file==null) return; // A delayed event cannot resurrect a deleted upload.
            if(!file.getFileMd5().equals(task.getFileMd5()) || !file.getUserId().equals(task.getUserId())) throw new SecurityException("Task upload identity mismatch");
        } else {
            if(task.getUserId()==null) throw new IllegalArgumentException("Legacy task is missing owner identity");
            var matches=fileUploadRepository.findAllByFileMd5AndUserIdOrderByCreatedAtDesc(task.getFileMd5(),task.getUserId());
            if(matches.isEmpty()) return;
            if(matches.size()!=1) throw new IllegalArgumentException("Ambiguous legacy upload task");
            file=matches.get(0);
        }
        try {
            file.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_PROCESSING);file.setVectorizationErrorMessage(null);fileUploadRepository.saveAndFlush(file);
            if(!fileTypeValidationService.isSupportedImageFileName(file.getFileName()) && documentVectorRepository.countByFileMd5(file.getFileMd5())==0) {
                try(InputStream stream=knowledgeUploads.getMergedFileStream(file.getFileMd5())) {
                    if(stream==null) throw new IOException("文件不可读");
                    parseService.parseAndSave(file.getFileMd5(),stream,file.getUserId(),file.getOrgTag(),file.isPublic());
                }
            }
            var sourceSnapshot=knowledgeSources.assemble(file);
            knowledgeIndexes.acceptSources(file.getId(),sourceSnapshot,file.getUserId(),FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType()));
        } catch(Exception error) {
            fileUploadRepository.findById(file.getId()).ifPresent(current->{current.setVectorizationStatus(FileUpload.VECTORIZATION_STATUS_FAILED);current.setVectorizationErrorMessage(error.getClass().getSimpleName());fileUploadRepository.save(current);});
            throw new IllegalStateException("Knowledge source preparation failed",error);
        }
    }

    private void clearPreviousIndexingData(String fileMd5,String owner) {
        try {
            elasticsearchService.deleteByFileMd5AndUserId(fileMd5,owner);
            log.info("上传任务解析前已清理 Elasticsearch 文档: {}", fileMd5);
        } catch (Exception e) {
            log.warn("上传任务解析前清理 Elasticsearch 失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }

        try {
            multimodalVectorizationService.deleteByFileMd5AndUserId(fileMd5,owner);
            log.info("上传任务解析前已清理多模态索引与元数据: {}", fileMd5);
        } catch (Exception e) {
            log.warn("上传任务解析前清理多模态索引失败: fileMd5={}, error={}", fileMd5, e.getMessage());
        }

        long previousChunkCount = documentVectorRepository.countByFileMd5(fileMd5);
        if (previousChunkCount > 0 && fileUploadRepository.countByFileMd5(fileMd5)<=1) {
            documentVectorRepository.deleteByFileMd5(fileMd5);
            log.info("上传任务解析前已清理旧文档切片: fileMd5={}, rows={}", fileMd5, previousChunkCount);
        }
    }

    private void processReindexTask(FileProcessingTask task) {
        try {
            if (isAlreadyIndexed(task.getFileMd5(),task.getUserId())) {
                log.info("跳过重复重建索引任务: fileMd5={}", task.getFileMd5());
                return;
            }

            String requesterId = task.getRequesterId() == null || task.getRequesterId().isBlank()
                    ? task.getUserId()
                    : task.getRequesterId();
            documentService.reindexDocument(task.getFileMd5(), requesterId);
        } catch (Exception e) {
            documentService.markVectorizationFailed(task.getFileMd5(),task.getUserId(), e);
            log.error("Error reindexing task: {}", task, e);
            throw new RuntimeException("Error reindexing task", e);
        }
    }

    private boolean isAlreadyIndexed(String fileMd5,String owner) {
        return fileUploadRepository.findFirstByFileMd5AndUserIdOrderByCreatedAtDesc(fileMd5,owner)
                .filter(file -> FileUpload.VECTORIZATION_STATUS_COMPLETED.equals(file.getVectorizationStatus()))
                .filter(file -> file.getActualChunkCount() == null || file.getActualChunkCount() > 0)
                .map(file -> hasExpectedElasticsearchDocuments(fileMd5, file.getActualChunkCount()))
                .orElse(false);
    }

    private boolean hasExpectedElasticsearchDocuments(String fileMd5, Integer expectedChunkCount) {
        try {
            long multimodalCount = multimodalVectorizationService.countByFileMd5(fileMd5);
            if (expectedChunkCount == null) {
                return multimodalCount > 0;
            }
            return multimodalCount >= expectedChunkCount;
        } catch (Exception e) {
            log.warn("检查 Elasticsearch 已索引状态失败: fileMd5={}, error={}", fileMd5, e.getMessage());
            return false;
        }
    }

    private VectorizationUsageResult toVectorizationUsageResult(
            MultimodalVectorizationService.MultimodalVectorizationResult multimodalResult) {
        if (multimodalResult == null) {
            return new VectorizationUsageResult(0, 0, "multimodal:unknown");
        }
        return new VectorizationUsageResult(
                multimodalResult.actualEmbeddingTokens(),
                multimodalResult.actualItemCount(),
                multimodalResult.modelVersion()
        );
    }

    /**
     * 模拟从存储系统下载文件
     *
     * @param filePath 文件路径或 URL
     * @return 文件输入流
     */
    private InputStream downloadFileFromStorage(String filePath) throws ServerException, InsufficientDataException, ErrorResponseException, IOException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        log.info("Downloading file from storage: {}", filePath);

        try {
            // 如果是文件系统路径
            File file = new File(filePath);
            if (file.exists()) {
                log.info("Detected file system path: {}", filePath);
                return new FileInputStream(file);
            }

            // 如果是远程 URL
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                log.info("Detected remote URL: {}", filePath);
                URL url = new URL(filePath);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(30000); // 连接超时30秒
                connection.setReadTimeout(180000);   // 读取超时时间3分钟

                // 添加必要的请求头
                connection.setRequestProperty("User-Agent", "SmartPAI-FileProcessor/1.0");

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    log.info("Successfully connected to URL, starting download...");
                    return connection.getInputStream();
                } else if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    log.error("Access forbidden - possible expired presigned URL");
                    throw new IOException("Access forbidden - the presigned URL may have expired");
                } else {
                    log.error("Failed to download file, HTTP response code: {} for URL: {}", responseCode, filePath);
                    throw new IOException(String.format("Failed to download file, HTTP response code: %d", responseCode));
                }
            }

            // 如果既不是文件路径也不是 URL
            throw new IllegalArgumentException("Unsupported file path format: " + filePath);
        } catch (Exception e) {
            log.error("Error downloading file from storage: {}", filePath, e);
            return null; // 或者抛出异常
        }
    }

}
