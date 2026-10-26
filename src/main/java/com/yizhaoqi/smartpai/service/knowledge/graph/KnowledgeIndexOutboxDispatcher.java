package com.yizhaoqi.smartpai.service.knowledge.graph;

import com.yizhaoqi.smartpai.config.GraphProtocolProperties;
import com.yizhaoqi.smartpai.model.GraphWorkMessage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.TimeUnit;

public final class KnowledgeIndexOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexOutboxDispatcher.class);
    private final GraphStore store;
    private final KafkaTemplate<String,Object> kafka;
    private final GraphProtocolProperties properties;
    public KnowledgeIndexOutboxDispatcher(GraphStore store, KafkaTemplate<String,Object> kafka, GraphProtocolProperties properties) {
        this.store=store; this.kafka=kafka; this.properties=properties;
    }
    @Scheduled(fixedDelayString="${knowledge.graph.dispatch-delay-ms:1000}")
    public void dispatchPending() {
        store.recoverExpired();
        for (var event : store.pendingEvents()) {
            String eventId = (String) event.get("id"), jobId = (String) event.get("job_id");
            try {
                kafka.executeInTransaction(template -> {
                    try {
                        template.send(properties.getTopic(), jobId, new GraphWorkMessage(eventId,jobId)).get(30, TimeUnit.SECONDS);
                        return null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException("Graph delivery interrupted",e);
                    } catch (Exception e) { throw new IllegalStateException("Graph delivery failed",e); }
                });
                store.delivered(eventId);
            } catch (Exception e) {
                store.deliveryFailed(eventId,e);
                log.warn("Graph outbox delivery failed: event={}",eventId,e);
                if (Thread.currentThread().isInterrupted()) return;
            }
        }
    }
}

