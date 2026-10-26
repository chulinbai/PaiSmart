package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.service.knowledge.KnowledgeIndexOrchestrator;
import com.yizhaoqi.smartpai.service.knowledge.graph.GraphTaskRunner;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeRagConfiguration {
    @Bean SmartInitializingSingleton registerKnowledgeBuilders(GraphTaskRunner runner,KnowledgeIndexOrchestrator orchestrator) {
        return ()->runner.setBuildRunner(claim->{
            try { orchestrator.execute(claim); }
            catch(RuntimeException e) { throw e; }
            catch(Exception e) { throw new IllegalStateException("Knowledge build failed",e); }
        });
    }
}
