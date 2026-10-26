package com.yizhaoqi.smartpai.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.MultiEmbeddingClient;
import com.yizhaoqi.smartpai.consumer.KnowledgeGraphWorkConsumer;
import com.yizhaoqi.smartpai.service.LlmProviderRouter;
import com.yizhaoqi.smartpai.service.ModelProviderConfigService;
import com.yizhaoqi.smartpai.service.knowledge.graph.*;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods=false)
@ConditionalOnExpression("${knowledge.graph.enabled:false} or ${knowledge.rag.enabled:false}")
@EnableConfigurationProperties(GraphProtocolProperties.class)
@EnableScheduling
public class GraphProtocolConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(org.springframework.scheduling.TaskScheduler.class)
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);scheduler.setThreadNamePrefix("knowledge-maintenance-");return scheduler;
    }
    @Bean GraphStore graphStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
            GraphProtocolProperties properties, KnowledgeRagProperties rag, DataSource dataSource) {
        if (properties.getLeaseSeconds() < 1 || properties.getMaxAttempts() < 1 || properties.getDispatchBatchSize() < 1)
            throw new IllegalArgumentException("Graph lease/retry/batch settings must be positive");
        if (properties.isInitializeSchema() || rag.isInitializeSchema())
            new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-graph.sql")).execute(dataSource);
        if (rag.isInitializeSchema())
            new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-orchestration.sql")).execute(dataSource);
        return new GraphStore(jdbc,mapper,manager,properties);
    }
    @Bean GraphDescriptionModel graphDescriptionModel(LlmProviderRouter router, ModelProviderConfigService providers,
            MultiEmbeddingClient embeddings, GraphProtocolProperties properties, GraphStore store) {
        return new ProviderGraphDescriptionModel(router,providers,embeddings,properties,store);
    }
    @Bean KnowledgeSearchIndexService graphSearchIndex(ElasticsearchClient client,MultiEmbeddingClient embeddings,ObjectMapper mapper,GraphProtocolProperties properties) {
        return new KnowledgeSearchIndexService(client,embeddings,mapper,properties);
    }
    @Bean GraphContributionService graphContributionService(GraphStore store,GraphDescriptionModel model) {
        return new GraphContributionService(store,model);
    }
    @Bean GraphMaintenanceService graphMaintenanceService(GraphContributionService contributions) { return new GraphMaintenanceService(contributions); }
    @Bean GraphMergeService graphMergeService(GraphStore store,GraphDescriptionModel model) { return new GraphMergeService(store,model); }
    @Bean IndexSyncWorker indexSyncWorker(GraphStore store,GraphSearchIndex index,GraphDescriptionModel model) { return new IndexSyncWorker(store,index,model); }
    @Bean GraphTaskRunner graphTaskRunner(GraphStore store,GraphMergeService merge,IndexSyncWorker sync) { return new GraphTaskRunner(store,merge,sync); }
    @Bean KnowledgeIndexOutboxDispatcher graphOutboxDispatcher(GraphStore store,KafkaTemplate<String,Object> kafka,GraphProtocolProperties properties) {
        return new KnowledgeIndexOutboxDispatcher(store,kafka,properties);
    }
    @Bean KnowledgeGraphWorkConsumer graphWorkConsumer(GraphTaskRunner runner) { return new KnowledgeGraphWorkConsumer(runner); }
    @Bean NewTopic graphWorkTopic(GraphProtocolProperties properties) {
        return TopicBuilder.name(properties.getTopic()).partitions(3).replicas(1).build();
    }
}

