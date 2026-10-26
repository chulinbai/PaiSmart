package com.yizhaoqi.smartpai.consumer;

import com.yizhaoqi.smartpai.model.GraphWorkMessage;
import com.yizhaoqi.smartpai.service.knowledge.graph.GraphTaskRunner;
import org.springframework.kafka.annotation.KafkaListener;

public final class KnowledgeGraphWorkConsumer {
    private final GraphTaskRunner runner;
    public KnowledgeGraphWorkConsumer(GraphTaskRunner runner) { this.runner=runner; }
    @KafkaListener(topics="${knowledge.graph.topic:knowledge-graph-work}",
        groupId="${knowledge.graph.consumer-group:knowledge-graph-workers}")
    public void receive(GraphWorkMessage message) { runner.execute(message.jobId()); }
}

