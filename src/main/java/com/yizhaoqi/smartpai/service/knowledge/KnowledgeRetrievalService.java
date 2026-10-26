package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.service.knowledge.graph.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeRetrievalService {
    private final Map<IndexType,KnowledgeRetriever> retrievers=new EnumMap<>(IndexType.class);
    private final KnowledgeAccessService access;private final KnowledgeArtifactService artifacts;private final GraphStore graph;
    public KnowledgeRetrievalService(List<KnowledgeRetriever> retrievers,KnowledgeAccessService access,KnowledgeArtifactService artifacts,GraphStore graph) {
        for(var retriever:retrievers) this.retrievers.put(retriever.type(),retriever);this.access=access;this.artifacts=artifacts;this.graph=graph;
    }
    public RetrievalResult retrieve(RetrievalRequest request,String requester) {
        long start=System.nanoTime();var identity=access.resolve(requester);
        var documents=access.documents(identity,request.documentIds());var retriever=retrievers.get(request.type());
        if(retriever==null || !retriever.capabilities().contains(request.mode())) throw new IllegalArgumentException("Unsupported retrieval mode");
        Coverage coverage=artifacts.coverage(documents,request.type(),request.mode());List<String> diagnostics=new ArrayList<>();
        try {
            if(Thread.currentThread().isInterrupted()) return result(Status.CANCELLED,List.of(),coverage,start,List.of("已取消"));
            var evidence=retriever.retrieve(request,identity,documents);
            if(Thread.currentThread().isInterrupted()) return result(Status.CANCELLED,List.of(),coverage,start,List.of("已取消"));
            List<Evidence> current=evidence.stream().filter(e->valid(e,identity)).toList();
            if(current.size()!=evidence.size()) diagnostics.add("部分候选已因版本或权限变化被移除");
            Status status=current.isEmpty()?(coverage.ready()==0 && coverage.authorized()>0?Status.NOT_READY:Status.EMPTY)
                :coverage.pending()>0 || coverage.failed()>0 || current.size()!=evidence.size()?Status.PARTIAL:Status.SUCCESS;
            return result(status,current,coverage,start,diagnostics);
        } catch(Exception error) {
            if(error instanceof InterruptedException) Thread.currentThread().interrupt();
            return result(Thread.currentThread().isInterrupted()?Status.CANCELLED:Status.FAILED,List.of(),coverage,start,List.of("检索未完成："+error.getClass().getSimpleName()));
        }
    }
    public boolean valid(Evidence evidence,Access identity) {
        try {
            for(var source:evidence.sources()) {
                var file=access.requireRead(source.documentId(),identity);
                if(!artifacts.sourceCurrent(source) || !access.sameScope(file,evidence.metadata().get("scopeId"))) return false;
            }
            if(evidence.metadata().containsKey("objectId")) {
                var head=graph.published(new ObjectKey(Kind.valueOf(evidence.metadata().get("kind")),evidence.metadata().get("objectId")));
                if(head==null || head.sourceVersion()!=evidence.version() || !head.projectionId().equals(evidence.metadata().get("projectionId"))) return false;
            }
            return true;
        } catch(Exception unavailable) { return false; }
    }
    public boolean validAll(Collection<Evidence> evidence,String requester) { var identity=access.resolve(requester);return evidence.stream().allMatch(e->valid(e,identity)); }
    private RetrievalResult result(Status status,List<Evidence> evidence,Coverage coverage,long start,List<String> diagnostics) {
        Map<String,String> versions=new LinkedHashMap<>();for(var e:evidence) versions.put(e.evidenceId(),Long.toString(e.version()));
        return new RetrievalResult(status,evidence,coverage,versions,(System.nanoTime()-start)/1_000_000,diagnostics);
    }
}
