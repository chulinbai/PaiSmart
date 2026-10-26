package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.knowledge.graph.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class GraphKnowledgeRetriever implements KnowledgeRetriever {
    private final KnowledgeSearchIndexService index;private final GraphStore store;private final JdbcTemplate jdbc;
    private final GraphKeywordExtractor keywords;private final KnowledgeChunkService chunks;
    private final KnowledgeAccessService access;private final KnowledgeArtifactService artifacts;private final GraphDescriptionModel model;
    public GraphKnowledgeRetriever(KnowledgeSearchIndexService index,GraphStore store,JdbcTemplate jdbc,GraphKeywordExtractor keywords,
            KnowledgeChunkService chunks,KnowledgeAccessService access,KnowledgeArtifactService artifacts,GraphDescriptionModel model) {
        this.index=index;this.store=store;this.jdbc=jdbc;this.keywords=keywords;this.chunks=chunks;this.access=access;this.artifacts=artifacts;this.model=model;
    }
    public IndexType type() { return IndexType.GRAPH; }
    public Set<String> capabilities() { return Set.of("local","global","hybrid"); }
    public List<Evidence> retrieve(RetrievalRequest request,Access identity,List<FileUpload> documents) throws Exception {
        if(documents.isEmpty()) return List.of();
        var scopes=documents.stream().map(access::scope).collect(Collectors.toSet());
        var allowed=documents.stream().collect(Collectors.toMap(FileUpload::getId,f->f));
        var terms=keywords.extract(request.query(),identity.userId());
        LinkedHashSet<ObjectKey> keys=new LinkedHashSet<>();
        for(Kind kind:Kind.values()) {
            if("local".equals(request.mode()) && kind==Kind.RELATION || "global".equals(request.mode()) && kind==Kind.ENTITY) continue;
            var hits=index.candidates(index.graphIndex(kind,false),kind==Kind.ENTITY?terms.entities():terms.topics(),"hybrid",scopes,List.of(),Math.min(100,request.topK()*5),identity.userId());
            for(var hit:hits) {
                var key=new ObjectKey(kind,String.valueOf(hit.get("objectId")));var head=store.published(key);
                if(head!=null && head.projectionId().equals(hit.get("projectionId")) && head.desiredConfig().equals(model.configVersion()) && scopes.contains(head.scopeId())) keys.add(key);
            }
        }
        // Expand both incoming and outgoing edges; never infer direction from co-occurrence.
        for(var key:List.copyOf(keys)) if(key.kind()==Kind.ENTITY) {
            var neighbors=jdbc.queryForList("SELECT id FROM kg_relation WHERE (source_entity_id=? OR target_entity_id=?) AND queryable=TRUE AND deleted=FALSE ORDER BY id LIMIT 40",String.class,key.id(),key.id());
            for(String id:neighbors) keys.add(new ObjectKey(Kind.RELATION,id));
        } else {
            var row=jdbc.queryForMap("SELECT source_entity_id,target_entity_id FROM kg_relation WHERE id=?",key.id());
            for(String field:List.of("source_entity_id","target_entity_id")) keys.add(new ObjectKey(Kind.ENTITY,(String)row.get(field)));
        }
        List<Evidence> result=new ArrayList<>();
        for(ObjectKey key:keys) {
            var head=store.published(key);
            if(head==null || !scopes.contains(head.scopeId()) || !head.desiredConfig().equals(model.configVersion())) continue;
            var rows=jdbc.queryForList("SELECT s.document_id,s.description,e.chunk_key FROM "+key.kind().sourceTable+" s JOIN kg_chunk_extraction e ON e.id=s.extraction_id WHERE s.object_id=? ORDER BY s.document_id,e.chunk_key,s.id",key.id());
            LinkedHashMap<String,SourceReference> references=new LinkedHashMap<>();List<String> selectedDescriptions=new ArrayList<>();boolean valid=true,subset=false;
            for(var row:rows) {
                long doc=((Number)row.get("document_id")).longValue();
                // Every source must still have the same authorization policy, including sources outside a requested subset.
                FileUpload file;
                try { file=access.requireRead(doc,identity); } catch(SecurityException denied) { valid=false;break; }
                if(!access.sameScope(file,head.scopeId())) { valid=false;break; }
                String chunkKey=(String)row.get("chunk_key");int separator=chunkKey.indexOf(':');
                if(separator<1) { valid=false;break; }
                long set;try { set=Long.parseLong(chunkKey.substring(0,separator)); } catch(NumberFormatException legacy) { valid=false;break; }
                var chunk=chunks.chunk(set,chunkKey.substring(separator+1));
                var ref=SourceReference.of(doc,set,chunk,file.getFileMd5(),file.getFileName());
                if(!artifacts.sourceCurrent(ref)) { valid=false;break; }
                if(!allowed.containsKey(doc)) { subset=true;continue; }
                references.putIfAbsent(ref.sourceId(),ref);selectedDescriptions.add((String)row.get("description"));
            }
            if(!valid || references.isEmpty()) continue;
            Map<String,String> metadata=new LinkedHashMap<>();metadata.put("objectId",key.id());metadata.put("kind",key.kind().name());metadata.put("scopeId",head.scopeId());metadata.put("projectionId",head.projectionId());
            String content=head.label()+"："+(subset?String.join("\n",selectedDescriptions):head.description());
            if(key.kind()==Kind.RELATION) {
                var relation=jdbc.queryForMap("SELECT source_entity_id,target_entity_id,predicate_label,direction_type FROM kg_relation WHERE id=?",key.id());
                for(var entry:relation.entrySet()) metadata.put(entry.getKey(),String.valueOf(entry.getValue()));
                var labels=jdbc.queryForList("SELECT id,label FROM kg_entity WHERE id IN (?,?)",relation.get("source_entity_id"),relation.get("target_entity_id"));
                Map<String,String> names=new HashMap<>();for(var label:labels) names.put((String)label.get("id"),(String)label.get("label"));
                content=names.get(relation.get("source_entity_id"))+" --["+relation.get("predicate_label")+", "+relation.get("direction_type")+"]--> "+names.get(relation.get("target_entity_id"))+"\n"+content;
            }
            result.add(new Evidence(hash("graph",key.kind().name(),key.id(),head.projectionId(),String.join(",",references.keySet())),"GRAPH_"+key.kind(),content,head.sourceVersion(),"graph/"+request.mode(),result.size()+1,new ArrayList<>(references.values()),metadata));
            if(result.size()>=request.topK()) break;
        }
        return List.copyOf(result);
    }
}
