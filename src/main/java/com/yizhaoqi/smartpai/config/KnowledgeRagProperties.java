package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties("knowledge.rag")
public class KnowledgeRagProperties {
    private boolean enabled;
    private boolean initializeSchema;
    private String parserVersion = "liteparse-chunks-images-v1";
    private String extractionVersion = "graph-extraction-v1";
    private String chunkIndexPrefix = "knowledge_chunks";
    private int embeddingBatchSize = 16;
    private int maxGraphInputCharacters = 20000;
    private int maxGraphFactsPerChunk = 100;
    private int evidenceCharacters = 24000;
    private int toolTimeoutSeconds = 90;
    private int maxDecisionRounds = 4;
    private int maxToolCalls = 8;
}
