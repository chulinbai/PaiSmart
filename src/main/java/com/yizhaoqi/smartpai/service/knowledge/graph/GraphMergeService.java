package com.yizhaoqi.smartpai.service.knowledge.graph;

import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

public final class GraphMergeService {
    private final GraphStore store;
    private final GraphDescriptionModel model;
    public GraphMergeService(GraphStore store, GraphDescriptionModel model) { this.store=store; this.model=model; }
    public void merge(Claim claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("LLM aggregation must run outside a database transaction");
        Snapshot snapshot = store.snapshot(claim);
        if (snapshot == null) { store.complete(claim, "SUPERSEDED"); return; }
        if (!model.configVersion().equals(claim.configVersion())) throw new IllegalStateException("Model configuration changed; rebuild required");
        String description = model.describe(snapshot, claim.requesterId(), claim.configVersion());
        store.accept(claim, description);
    }
}

