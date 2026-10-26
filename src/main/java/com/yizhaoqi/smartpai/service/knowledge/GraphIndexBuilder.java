package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.knowledge.graph.GraphDescriptionModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class GraphIndexBuilder implements KnowledgeIndexBuilder {
    private final GraphExtractionService extraction; private final GraphDescriptionModel descriptions;
    public GraphIndexBuilder(GraphExtractionService extraction,GraphDescriptionModel descriptions) { this.extraction=extraction;this.descriptions=descriptions; }
    public IndexType type() { return IndexType.GRAPH; }
    public Set<String> capabilities() { return Set.of("local","global","hybrid"); }
    public String configVersion() { return hash(extraction.configVersion(),descriptions.configVersion()); }
    public BuildResult build(BuildContext context,ChunkSet set,FileUpload file) throws Exception {
        if(!configVersion().equals(context.configVersion())) throw new IllegalStateException("Graph configuration changed; rebuild required");
        List<ChunkExtraction> results=new ArrayList<>();
        for(Chunk chunk:set.chunks()) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Graph build cancelled");
            results.add(extraction.extract(context,file,chunk));
        }
        if(!configVersion().equals(context.configVersion())) throw new IllegalStateException("Graph configuration changed during extraction");
        return new BuildResult("graph",results.size(),0,new DocumentBatch(file.getId(),context.buildRevision(),results));
    }
}
