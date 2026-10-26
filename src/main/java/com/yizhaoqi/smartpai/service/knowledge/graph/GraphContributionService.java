package com.yizhaoqi.smartpai.service.knowledge.graph;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.function.Supplier;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

/** All changes to effective sources, including deletion, pass through this transaction boundary. */
public final class GraphContributionService {
    private final GraphStore store;
    private final GraphDescriptionModel model;
    public GraphContributionService(GraphStore store, GraphDescriptionModel model) { this.store = store; this.model = model; }

    public Change replace(DocumentBatch batch, String requesterId) {
        required(requesterId);
        String config = model.configVersion();
        String payload = store.json(batch);
        String fingerprint = hash(payload);
        return retry(() -> store.transaction(() -> replaceInTransaction(batch, requesterId, config, payload, fingerprint)));
    }
    private Change replaceInTransaction(DocumentBatch batch, String requester, String config, String payload, String fingerprint) {
        GraphStore.DocumentAccess access = store.lockDocument(batch.documentId(), requester);
        store.jdbc.update("INSERT INTO kg_document_state (document_id,source_revision,batch_hash,scope_id,payload_json) " +
            "VALUES (?,0,'',?,'{}') ON DUPLICATE KEY UPDATE document_id=document_id", batch.documentId(), access.scopeId());
        Map<String,Object> state = store.jdbc.queryForMap("SELECT * FROM kg_document_state WHERE document_id=? FOR UPDATE", batch.documentId());
        if (Boolean.TRUE.equals(state.get("deleted")) || ((Number)state.get("source_revision")).longValue() > batch.sourceRevision())
            return new Change(false, List.of());
        if (((Number)state.get("source_revision")).longValue() == batch.sourceRevision() && !fingerprint.equals(state.get("batch_hash")))
            throw new IllegalArgumentException("A document revision is immutable; use a newer sourceRevision");

        List<Prepared> contributions = new ArrayList<>();
        for (ChunkExtraction chunk : batch.chunks()) {
            if (chunk.chunkKey().length() > 255) throw new IllegalArgumentException("Chunk key is too long");
            String inputHash = hash(chunk.sourceText(), chunk.extractionConfig());
            String extractionId = hash(Long.toString(batch.documentId()), Long.toString(batch.sourceRevision()), chunk.chunkKey(), inputHash);
            store.jdbc.update("INSERT INTO kg_chunk_extraction (id,document_id,source_revision,chunk_key,input_hash,result_json)" +
                " VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                extractionId, batch.documentId(), batch.sourceRevision(), chunk.chunkKey(), inputHash, store.json(chunk));
            List<Fact> facts = new ArrayList<>(chunk.facts());
            // Relationship evidence also supports its endpoints. Removing an entity must not leave dangling valid edges.
            for (Fact fact : chunk.facts()) if (fact.kind() == Kind.RELATION) {
                facts.add(Fact.entity(fact.source(), fact.source() + " — " + fact.description()));
                facts.add(Fact.entity(fact.target(), fact.target() + " — " + fact.description()));
            }
            for (Fact fact : facts) {
                String factJson = store.json(fact);
                ObjectKey key = new ObjectKey(fact.kind(), hash(access.scopeId(), fact.identity()));
                contributions.add(new Prepared(key, fact, extractionId, hash(key.id(), extractionId, factJson), factJson));
            }
        }
        Map<ObjectKey,Fact> descriptors = new TreeMap<>();
        for (Prepared p : contributions) descriptors.putIfAbsent(p.key(), p.fact());
        Set<ObjectKey> objects = store.documentObjects(batch.documentId());
        objects.addAll(descriptors.keySet());
        // Every writer takes object locks in the same order, including new entity identity insertion.
        for (ObjectKey key : objects) {
            if (descriptors.containsKey(key)) store.ensureObject(key, access.scopeId(), descriptors.get(key), config);
            store.head(key, true);
        }
        Map<ObjectKey,Set<String>> before = sourceIds(batch.documentId(), objects);
        for (Kind kind : Kind.values()) store.jdbc.update("DELETE FROM " + kind.sourceTable + " WHERE document_id=?", batch.documentId());
        for (Prepared p : contributions) store.jdbc.update("INSERT INTO " + p.key().kind().sourceTable +
            " (id,object_id,document_id,extraction_id,fact_json,description) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            p.id(), p.key().id(), batch.documentId(), p.extractionId(), p.factJson(), p.fact().description());
        Map<ObjectKey,Set<String>> after = sourceIds(batch.documentId(), objects);
        List<ObjectKey> changed = new ArrayList<>();
        for (ObjectKey key : objects) {
            boolean sourceChanged = !before.get(key).equals(after.get(key));
            Head head = store.head(key, false);
            if (sourceChanged || !head.desiredConfig().equals(config)) {
                advance(key, sourceChanged, config, requester);
                changed.add(key);
            }
        }
        store.jdbc.update("UPDATE kg_document_state SET source_revision=?,batch_hash=?,scope_id=?,payload_json=? WHERE document_id=?",
            batch.sourceRevision(), fingerprint, access.scopeId(), payload, batch.documentId());
        return new Change(!changed.isEmpty(), changed);
    }
    private Map<ObjectKey,Set<String>> sourceIds(long documentId, Set<ObjectKey> keys) {
        Map<ObjectKey,Set<String>> result = new HashMap<>();
        for (ObjectKey key : keys) result.put(key, new HashSet<>(store.jdbc.queryForList(
            "SELECT id FROM " + key.kind().sourceTable + " WHERE document_id=? AND object_id=?", String.class, documentId, key.id())));
        return result;
    }
    private void advance(ObjectKey key, boolean sourceChanged, String config, String requester) {
        Long count = store.jdbc.queryForObject("SELECT COUNT(*) FROM " + key.kind().sourceTable + " WHERE object_id=?", Long.class, key.id());
        boolean deleted = count == null || count == 0;
        store.jdbc.update("UPDATE " + key.kind().table + " SET source_version=source_version+?,build_revision=build_revision+1,desired_config=?,deleted=?,queryable=FALSE WHERE id=?",
            sourceChanged ? 1 : 0, config, deleted, key.id());
        Head head = store.head(key, false);
        store.enqueue(deleted ? WorkType.DELETE : WorkType.MERGE, key, head.sourceVersion(), config, requester, "{}", "");
    }
    public Change withdraw(long documentId, String requesterId) {
        return withdraw(documentId,requesterId,true);
    }
    public Change suspend(long documentId,String requesterId) { return withdraw(documentId,requesterId,false); }
    private Change withdraw(long documentId,String requesterId,boolean tombstone) {
        return retry(() -> store.transaction(() -> {
            store.lockDocument(documentId, requesterId);
            List<Map<String,Object>> states = store.jdbc.queryForList("SELECT * FROM kg_document_state WHERE document_id=? FOR UPDATE", documentId);
            if (states.isEmpty() || Boolean.TRUE.equals(states.get(0).get("deleted"))) return new Change(false, List.of());
            Set<ObjectKey> objects = store.documentObjects(documentId);
            for (ObjectKey key : objects) store.head(key, true);
            for (Kind kind : Kind.values()) store.jdbc.update("DELETE FROM " + kind.sourceTable + " WHERE document_id=?", documentId);
            for (ObjectKey key : objects) advance(key, true, store.head(key, false).desiredConfig(), requesterId);
            store.jdbc.update("UPDATE kg_document_state SET deleted=? WHERE document_id=?", tombstone,documentId);
            return new Change(true, List.copyOf(objects));
        }));
    }
    /** Re-evaluates current file ACL and model configuration without inventing a new content revision. */
    public Change refresh(long documentId, String requesterId) {
        return retry(() -> store.transaction(() -> {
            store.lockDocument(documentId, requesterId);
            List<String> payloads = store.jdbc.queryForList("SELECT payload_json FROM kg_document_state WHERE document_id=? AND deleted=FALSE FOR UPDATE",
                String.class, documentId);
            if (payloads.isEmpty()) return new Change(false, List.of());
            return replace(store.fromJson(payloads.get(0), DocumentBatch.class), requesterId);
        }));
    }
    private <T> T retry(Supplier<T> action) {
        int limit = TransactionSynchronizationManager.isActualTransactionActive() ? 1 : 3;
        for (int attempt=1;;attempt++) try { return action.get(); }
        catch (TransientDataAccessException conflict) { if (attempt >= limit) throw conflict; }
    }
    private record Prepared(ObjectKey key, Fact fact, String extractionId, String id, String factJson) {}
}

