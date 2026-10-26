package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.knowledge.graph.KnowledgeSearchIndexService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.hash;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class ChunkKnowledgeRetriever implements KnowledgeRetriever {
    private final KnowledgeSearchIndexService index;private final KnowledgeArtifactService artifacts;private final KnowledgeChunkService chunks;
    private final KnowledgeAccessService access;private final KnowledgeRagProperties properties;
    public ChunkKnowledgeRetriever(KnowledgeSearchIndexService index,KnowledgeArtifactService artifacts,KnowledgeChunkService chunks,KnowledgeAccessService access,KnowledgeRagProperties properties) {
        this.index=index;this.artifacts=artifacts;this.chunks=chunks;this.access=access;this.properties=properties;
    }
    public IndexType type() { return IndexType.CHUNK; }
    public Set<String> capabilities() { return Set.of("vector","keyword","hybrid"); }
    public List<Evidence> retrieve(RetrievalRequest request,Access identity,List<FileUpload> documents) throws Exception {
        if(documents.isEmpty()) return List.of();
        var files=documents.stream().collect(Collectors.toMap(FileUpload::getId,f->f));
        var scopes=documents.stream().map(access::scope).collect(Collectors.toSet());
        String name="keyword".equals(request.mode())?properties.getChunkIndexPrefix()+"_*":index.chunkIndexName(properties.getChunkIndexPrefix(),index.modelVersion());
        var hits=index.candidates(name,request.query(),request.mode(),scopes,new ArrayList<>(files.keySet()),Math.min(200,request.topK()*10),identity.userId());
        List<Evidence> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(var hit:hits) {
            long doc=((Number)hit.get("documentId")).longValue(),set=((Number)hit.get("chunkSetId")).longValue(),revision=((Number)hit.get("buildRevision")).longValue();
            var file=files.get(doc);if(file==null || !access.sameScope(access.requireRead(doc,identity),String.valueOf(hit.get("scopeId")))) continue;
            var state=artifacts.state(doc,IndexType.CHUNK,request.mode());
            if(!"READY".equals(state.get("phase")) || !Objects.equals(state.get("config_version"),hit.get("configVersion"))
                || state.get("ready_chunk_set")==null || ((Number)state.get("ready_chunk_set")).longValue()!=set
                || ((Number)state.get("ready_build_revision")).longValue()!=revision) continue;
            var chunk=chunks.chunk(set,String.valueOf(hit.get("chunkKey")));
            var source=SourceReference.of(doc,set,chunk,file.getFileMd5(),file.getFileName());
            if(!artifacts.sourceCurrent(source) || !seen.add(source.sourceId())) continue;
            result.add(new Evidence(hash("chunk",source.sourceId()),chunk.type(),chunk.text(),revision,"chunk/"+request.mode(),result.size()+1,List.of(source),Map.of("scopeId",String.valueOf(hit.get("scopeId")))));
            if(result.size()>=request.topK()) break;
        }
        return List.copyOf(result);
    }
}
