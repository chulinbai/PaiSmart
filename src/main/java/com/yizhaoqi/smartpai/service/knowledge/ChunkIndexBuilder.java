package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.client.MultiEmbeddingClient;
import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.knowledge.graph.KnowledgeSearchIndexService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.hash;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class ChunkIndexBuilder implements KnowledgeIndexBuilder {
    private final MultiEmbeddingClient embeddings; private final KnowledgeSourceAssembler sources;
    private final KnowledgeSearchIndexService index; private final KnowledgeRagProperties properties;
    public ChunkIndexBuilder(MultiEmbeddingClient embeddings,KnowledgeSourceAssembler sources,KnowledgeSearchIndexService index,KnowledgeRagProperties properties) {
        this.embeddings=embeddings;this.sources=sources;this.index=index;this.properties=properties;
    }
    public IndexType type() { return IndexType.CHUNK; }
    public Set<String> capabilities() { return Set.of("vector","keyword","hybrid"); }
    public String configVersion() { return hash("chunk-v1",properties.getChunkIndexPrefix(),embeddings.currentModelVersion()); }
    public BuildResult build(BuildContext context,ChunkSet set,FileUpload file) throws Exception {
        String model=embeddings.currentModelVersion();
        if(!context.configVersion().equals(configVersion())) throw new IllegalStateException("Chunk model changed; rebuild required");
        String physical=index.chunkIndexName(properties.getChunkIndexPrefix(),model); int tokens=0;
        int batch=Math.max(1,Math.min(64,properties.getEmbeddingBatchSize()));
        for(int offset=0;offset<set.chunks().size();offset+=batch) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedException("Build cancelled");
            var chunks=set.chunks().subList(offset,Math.min(set.chunks().size(),offset+batch));
            List<MultiEmbeddingClient.EmbeddingInput> inputs=new ArrayList<>();
            for(var chunk:chunks) inputs.add(sources.embeddingInput(file,chunk));
            var result=embeddings.embedWithUsage(inputs,context.requesterId(),MultiEmbeddingClient.UsageType.UPLOAD);
            if(!model.equals(result.modelVersion())) throw new IllegalStateException("Embedding configuration changed");
            index.writeChunks(physical,context,chunks,result.vectors(),model); tokens+=result.totalTokens();
        }
        return new BuildResult(physical,set.chunks().size(),tokens);
    }
}
