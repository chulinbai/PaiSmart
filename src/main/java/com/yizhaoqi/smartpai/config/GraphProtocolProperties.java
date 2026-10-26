package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("knowledge.graph")
public class GraphProtocolProperties {
    private boolean enabled = false;
    private boolean initializeSchema = false;
    private String topic = "knowledge-graph-work";
    private String consumerGroup = "knowledge-graph-workers";
    private String indexPrefix = "knowledge_graph";
    private String promptVersion = "graph-description-v1";
    private long leaseSeconds = 600;
    private int maxAttempts = 5;
    private int dispatchBatchSize = 32;
    private int maxPromptCharacters = 100000;
    private int maxCompletionTokens = 2048;
}

