package com.yizhaoqi.smartpai.service.knowledge;

import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.hash;

public final class KnowledgeTypes {
    private KnowledgeTypes() {}
    public enum IndexType { CHUNK, GRAPH }
    public enum Status { SUCCESS, EMPTY, PARTIAL, NOT_READY, FAILED, CANCELLED }
    public record Chunk(String key,String type,String text,Integer chunkId,Integer pageNumber,String anchorText,
            Integer imageSequence,String imageSourceType,String bucketName,String objectName,String contentType,Integer width,Integer height) {
        public Chunk { if(key==null || key.isBlank() || type==null || text==null) throw new IllegalArgumentException("Invalid chunk"); }
        public boolean image() { return !"TEXT_CHUNK".equals(type); }
    }
    public record ChunkSet(long id,long documentId,String fingerprint,String parserConfig,List<Chunk> chunks) {
        public ChunkSet { chunks=List.copyOf(chunks); }
    }
    public record Access(String userId,Set<String> orgTags) { public Access { orgTags=Set.copyOf(orgTags); } }
    public record SourceReference(String sourceId,long documentId,long chunkSetId,String chunkKey,String fileMd5,String fileName,
            String type,String text,Integer chunkId,Integer pageNumber,String anchorText,Integer imageSequence,
            String imageSourceType,String bucketName,String objectName,String contentType,Integer width,Integer height) {
        public static SourceReference of(long doc,long set,Chunk c,String md5,String name) {
            return new SourceReference(hash(Long.toString(doc),Long.toString(set),c.key(),c.type()),doc,set,c.key(),md5,name,
                c.type(),c.text(),c.chunkId(),c.pageNumber(),c.anchorText(),c.imageSequence(),c.imageSourceType(),c.bucketName(),c.objectName(),c.contentType(),c.width(),c.height());
        }
    }
    public record Evidence(String evidenceId,String type,String content,long version,String retrievalPath,int rank,
            List<SourceReference> sources,Map<String,String> metadata) {
        public Evidence { sources=List.copyOf(sources); metadata=Map.copyOf(metadata); }
    }
    public record Coverage(int authorized,int ready,int pending,int failed) {}
    public record RetrievalRequest(IndexType type,String query,String mode,int topK,List<Long> documentIds) {
        public RetrievalRequest {
            if(type==null || query==null || query.isBlank() || query.length()>8000) throw new IllegalArgumentException("检索类型和 query 必填（最多 8000 字符）");
            query=query.trim(); topK=topK==0?5:topK;
            if(topK<1 || topK>20) throw new IllegalArgumentException("topK 必须为 1 到 20");
            mode=mode==null?"hybrid":mode.toLowerCase(Locale.ROOT);
            documentIds=documentIds==null?List.of():List.copyOf(new TreeSet<>(documentIds));
            if(documentIds.size()>200) throw new IllegalArgumentException("一次最多指定 200 个文档");
        }
    }
    public record RetrievalResult(Status executionStatus,List<Evidence> evidence,Coverage coverage,
            Map<String,String> artifactVersions,long elapsedMillis,List<String> diagnostics) {
        public RetrievalResult { evidence=List.copyOf(evidence); artifactVersions=Map.copyOf(artifactVersions); diagnostics=List.copyOf(diagnostics); }
    }
    public record BuildContext(long documentId,long chunkSetId,long buildRevision,String configVersion,String scopeId,String requesterId) {}
    public record BuildResult(String physicalIndex,int itemCount,int embeddingTokens,
            com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.DocumentBatch graphBatch) {
        public BuildResult(String index,int count,int tokens) { this(index,count,tokens,null); }
    }
}
