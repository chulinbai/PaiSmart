package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.service.knowledge.graph.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

/** Builds use exactly the same durable jobs, leases, fencing and outbox as graph merge/sync. */
@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeIndexTaskService {
    private final GraphStore store;
    public KnowledgeIndexTaskService(GraphStore store) { this.store=store; }
    public String enqueue(IndexType type,BuildContext context) {
        return store.enqueueTask(type==IndexType.CHUNK?WorkType.BUILD_CHUNK:WorkType.BUILD_GRAPH,
            new ObjectKey(Kind.ENTITY,Long.toString(context.documentId())),context.chunkSetId(),context.buildRevision(),
            context.configVersion(),context.requesterId(),store.json(context),"");
    }
    public boolean fenced(Claim claim,Supplier<Boolean> action) {
        return store.transaction(()-> {
            if(!store.owns(claim)) return false;
            boolean accepted=action.get(); store.finish(claim,accepted?"SUCCEEDED":"SUPERSEDED"); return accepted;
        });
    }
    public BuildContext context(Claim claim) { return store.fromJson(claim.payload(),BuildContext.class); }
}
