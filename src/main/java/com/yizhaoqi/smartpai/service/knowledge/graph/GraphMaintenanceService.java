package com.yizhaoqi.smartpai.service.knowledge.graph;

import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

/** Lifecycle operations share exactly the same contribution/lock/version protocol as ingestion. */
public final class GraphMaintenanceService {
    private final GraphContributionService contributions;
    public GraphMaintenanceService(GraphContributionService contributions) { this.contributions = contributions; }
    public Change removeDocumentContribution(long documentId, String requesterId) {
        return contributions.withdraw(documentId, requesterId);
    }
    public Change refreshPermissionsAndConfiguration(long documentId, String requesterId) {
        return contributions.refresh(documentId, requesterId);
    }
}

