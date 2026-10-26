package com.yizhaoqi.smartpai.service.knowledge.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

public final class GraphTaskRunner {
    private static final Logger log = LoggerFactory.getLogger(GraphTaskRunner.class);
    private final GraphStore store;
    private final GraphMergeService merge;
    private final IndexSyncWorker sync;
    private java.util.function.Consumer<Claim> buildRunner;
    private final java.util.concurrent.ConcurrentMap<String,Claim> running=new java.util.concurrent.ConcurrentHashMap<>();
    public GraphTaskRunner(GraphStore store, GraphMergeService merge, IndexSyncWorker sync) {
        this.store=store; this.merge=merge; this.sync=sync;
    }
    public void setBuildRunner(java.util.function.Consumer<Claim> runner) { this.buildRunner=runner; }
    public void execute(String jobId) {
        Claim claim = store.claim(jobId);
        if (claim == null) return;
        running.put(claim.jobId(),claim);
        try {
            if (claim.type() == WorkType.BUILD_CHUNK || claim.type()==WorkType.BUILD_GRAPH || claim.type()==WorkType.DELETE_CHUNKS) {
                if(buildRunner==null) throw new IllegalStateException("Knowledge builders are disabled");
                buildRunner.accept(claim);
            } else if (claim.type() == WorkType.MERGE) merge.merge(claim); else sync.sync(claim);
        } catch (Exception error) {
            store.failed(claim, error);
            log.warn("Graph task failed: job={}, type={}, error={}", jobId, claim.type(), error.toString());
        } finally {
            running.remove(claim.jobId(),claim);
        }
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${knowledge.graph.heartbeat-ms:10000}")
    public void renewLeases() {
        for(var claim:running.values()) try { if(!store.renew(claim)) running.remove(claim.jobId(),claim); }
        catch(Exception error) { log.warn("Knowledge lease renewal failed: job={}",claim.jobId()); }
    }
}

