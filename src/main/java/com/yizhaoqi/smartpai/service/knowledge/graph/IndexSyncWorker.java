package com.yizhaoqi.smartpai.service.knowledge.graph;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

public final class IndexSyncWorker {
    private final GraphStore store;
    private final GraphSearchIndex index;
    private final GraphDescriptionModel model;
    public IndexSyncWorker(GraphStore store, GraphSearchIndex index, GraphDescriptionModel model) {
        this.store=store; this.index=index; this.model=model;
    }
    public void sync(Claim claim) throws Exception {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("ES synchronization must run outside a database transaction");
        if (claim.type() == WorkType.DELETE) {
            if (!store.current(claim, null, true)) { store.complete(claim, "SUPERSEDED"); return; }
            index.deleteThrough(claim.key(), claim.sourceVersion());
            store.complete(claim, "SUCCEEDED");
            return;
        }
        Projection projection = store.fromJson(claim.payload(), Projection.class);
        if (!store.current(claim, projection.projectionId(), false)) { store.complete(claim, "SUPERSEDED"); return; }
        if (!model.configVersion().equals(claim.configVersion())) throw new IllegalStateException("Index configuration changed; rebuild required");
        index.write(projection, claim.requesterId());
        if (!store.publish(claim, projection)) {
            // A newer lease may still need the same immutable projection: never delete it just for lease loss.
            if (!store.current(claim, projection.projectionId(), false)) index.discard(projection);
        }
    }
}

