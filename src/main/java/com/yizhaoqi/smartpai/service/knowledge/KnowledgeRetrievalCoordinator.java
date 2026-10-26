package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

/** State only: the existing ChatHandler retains tool decisions, cancellation and answer generation. */
@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeRetrievalCoordinator {
    private final Map<String,KnowledgeRetrievalState> runs=new ConcurrentHashMap<>();
    private final KnowledgeRetrievalService retrieval;private final KnowledgeRagProperties properties;
    private final EvidenceAssembler assembler=new EvidenceAssembler();
    public KnowledgeRetrievalCoordinator(KnowledgeRetrievalService retrieval,KnowledgeRagProperties properties) { this.retrieval=retrieval;this.properties=properties; }
    private KnowledgeRetrievalState state(String generation) { return runs.computeIfAbsent(generation,KnowledgeRetrievalState::new); }
    public com.yizhaoqi.smartpai.service.AgentToolRegistry.ToolExecutionResult retrieve(String generation,String name,Map<String,Object> args,
            String user,java.util.function.Supplier<com.yizhaoqi.smartpai.service.AgentToolRegistry.ToolExecutionResult> action) {
        var state=state(generation);String key=name+":"+new TreeMap<>(args);
        var cached=state.cache.get(key);
        if(cached!=null && retrieval.validAll(cached.evidence(),user))
            return new com.yizhaoqi.smartpai.service.AgentToolRegistry.ToolExecutionResult(name,true,"缓存检索证据",Map.of(),false,cached);
        var result=action.get();
        if(result.retrievalResult()!=null && !result.retrievalResult().evidence().isEmpty()) state.cache.put(key,result.retrievalResult());
        return result;
    }
    public EvidenceAssembler.Assembly merge(String generation,RetrievalResult result) {
        var state=state(generation);state.evidence.add(result.evidence());
        for(var e:result.evidence().stream().sorted(Comparator.comparingInt(Evidence::rank).thenComparing(Evidence::evidenceId)).toList()) for(var source:e.sources()) state.citations.register(source);
        var content=assembler.assemble(result.evidence(),state.citations,properties.getEvidenceCharacters());
        return new EvidenceAssembler.Assembly("执行状态："+result.executionStatus()+"；覆盖："+result.coverage()+"；诊断："+result.diagnostics()+"\n"+content.text(),content.sources());
    }
    public EvidenceAssembler.Assembly finalEvidence(String generation) { var state=state(generation);return assembler.assemble(state.evidence.ranked(),state.citations,properties.getEvidenceCharacters()); }
    public Map<Integer,SearchResult> references(String generation) {
        Map<Integer,SearchResult> result=new LinkedHashMap<>();state(generation).citations.sources().forEach((number,source)->result.put(number,assembler.searchResult(source,"KNOWLEDGE_EVIDENCE")));return result;
    }
    public void verify(String generation,String user) {
        var state=runs.get(generation);
        if(state!=null && !retrieval.validAll(state.evidence.values(),user)) throw new SecurityException("本轮资料权限或版本已变化，请重新提问");
    }
    public void close(String generation) { runs.remove(generation); }
}
