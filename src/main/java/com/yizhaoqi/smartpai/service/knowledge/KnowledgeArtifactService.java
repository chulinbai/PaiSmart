package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.knowledge.graph.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeArtifactService {
    private final JdbcTemplate jdbc;private final GraphStore store;
    private final Map<IndexType,KnowledgeIndexBuilder> builders=new EnumMap<>(IndexType.class);
    public KnowledgeArtifactService(JdbcTemplate jdbc,GraphStore store,List<KnowledgeIndexBuilder> builders) {
        this.jdbc=jdbc;this.store=store;for(var builder:builders) this.builders.put(builder.type(),builder);
    }
    public List<Map<String,Object>> states(long documentId) {
        return states(documentId,false);
    }
    private List<Map<String,Object>> states(long documentId,boolean allowKeywordGeneration) {
        var states=jdbc.queryForList("SELECT s.*,w.work_status,w.attempts,w.last_error AS task_error FROM knowledge_index_state s LEFT JOIN kg_work w ON w.id=s.job_id WHERE s.document_id=? ORDER BY s.index_type",documentId);
        for(var row:states) {
            if("FAILED".equals(row.get("work_status")) && !"REVOKED".equals(row.get("phase"))) { row.put("phase","FAILED");row.put("last_error",row.get("task_error")); }
            if("GRAPH".equals(row.get("index_type")) && "SUCCEEDED".equals(row.get("work_status")) && Set.of("SYNCING","READY","FAILED").contains(row.get("phase"))) {
                boolean ready=true;String failure=null;
                for(Kind kind:Kind.values()) {
                    var ids=jdbc.queryForList("SELECT DISTINCT object_id FROM "+kind.sourceTable+" WHERE document_id=?",String.class,documentId);
                    for(String id:ids) if(store.published(new ObjectKey(kind,id))==null) {
                        ready=false;
                        var errors=jdbc.queryForList("SELECT w.last_error FROM kg_work w JOIN "+kind.table+" h ON h.id=w.object_id AND h.source_version=w.source_version AND h.build_revision=w.build_revision WHERE w.object_kind=? AND h.id=? AND w.work_status='FAILED'",String.class,kind.name(),id);
                        if(!errors.isEmpty()) failure=errors.get(0);
                    }
                }
                String phase=failure!=null?"FAILED":ready?"READY":"SYNCING";
                row.put("phase",phase);row.put("last_error",failure);
                // Persist only for the generation inspected; a concurrent rebuild cannot be marked READY.
                jdbc.update("UPDATE knowledge_index_state SET phase=?,last_error=? WHERE document_id=? AND index_type='GRAPH' AND build_revision=? AND phase IN ('SYNCING','READY','FAILED')",phase,failure,documentId,row.get("build_revision"));
                if(ready) jdbc.update("UPDATE knowledge_artifact SET service_status='AVAILABLE',published_generation=GREATEST(published_generation,?) WHERE id=?",row.get("build_revision"),hash("GRAPH",String.valueOf(row.get("scope_id"))));
            }
            var type=IndexType.valueOf((String)row.get("index_type"));
            if("READY".equals(row.get("phase")) && !(allowKeywordGeneration && type==IndexType.CHUNK)
                    && !builders.get(type).configVersion().equals(row.get("config_version"))) {
                row.put("phase","NOT_READY");row.put("last_error","模型或索引配置已变化，请重建此索引");
            }
        }
        return states;
    }
    public Map<String,Object> state(long doc,IndexType type) { return states(doc).stream().filter(s->type.name().equals(s.get("index_type"))).findFirst().orElse(Map.of()); }
    public Map<String,Object> state(long doc,IndexType type,String mode) { return states(doc,"keyword".equals(mode)).stream().filter(s->type.name().equals(s.get("index_type"))).findFirst().orElse(Map.of()); }
    public Coverage coverage(List<FileUpload> files,IndexType type) {
        return coverage(files,type,"hybrid");
    }
    public Coverage coverage(List<FileUpload> files,IndexType type,String mode) {
        int ready=0,failed=0;
        for(var file:files) { var s=state(file.getId(),type,mode); if("READY".equals(s.get("phase"))) ready++; else if("FAILED".equals(s.get("phase"))) failed++; }
        return new Coverage(files.size(),ready,files.size()-ready-failed,failed);
    }
    public List<Map<String,Object>> artifacts(Set<String> scopes) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(String scope:scopes) for(var artifact:jdbc.queryForList("SELECT * FROM knowledge_artifact WHERE scope_id=? ORDER BY index_type",scope)) {
            var type=IndexType.valueOf((String)artifact.get("index_type"));
            var documents=jdbc.queryForList("SELECT document_id FROM knowledge_index_state WHERE scope_id=? AND index_type=?",Long.class,scope,type.name());
            boolean available=documents.stream().anyMatch(id->"READY".equals(state(id,type).get("phase")));
            artifact.put("service_status",available?"AVAILABLE":"NOT_READY");result.add(artifact);
        }
        return result;
    }
    public boolean sourceCurrent(SourceReference source) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)>0 FROM knowledge_document WHERE document_id=? AND current_chunk_set=? AND deleted=FALSE",Boolean.class,source.documentId(),source.chunkSetId()));
    }
}
