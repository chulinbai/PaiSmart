package com.yizhaoqi.smartpai.service.knowledge.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.GraphProtocolProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

/** Explicit SQL keeps row locks, fencing and publication conditions in one audited boundary. */
public final class GraphStore {
    final JdbcTemplate jdbc;
    final ObjectMapper mapper;
    final GraphProtocolProperties properties;
    private final TransactionTemplate write;
    private final TransactionTemplate snapshot;

    public GraphStore(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager manager,
                      GraphProtocolProperties properties) {
        this.jdbc = jdbc; this.mapper = mapper; this.properties = properties;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        snapshot = new TransactionTemplate(manager);
        snapshot.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
    }
    public <T> T transaction(Supplier<T> action) { return write.execute(status -> action.get()); }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Invalid graph payload", e); }
    }
    public <T> T fromJson(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored graph payload", e); }
    }
    static String scope(String owner, String org, boolean publicDocument) {
        return publicDocument ? hash("PUBLIC") : hash("OWNER_OR_ORG", owner, org);
    }
    record DocumentAccess(String owner, String scopeId) {}
    DocumentAccess lockDocument(long id, String requester) {
        List<DocumentAccess> rows = jdbc.query(
            "SELECT user_id, org_tag, is_public FROM file_upload WHERE id=? FOR UPDATE",
            (rs, n) -> new DocumentAccess(rs.getString(1), scope(rs.getString(1), rs.getString(2), rs.getBoolean(3))), id);
        if (rows.isEmpty()) throw new IllegalArgumentException("Document does not exist");
        DocumentAccess access = rows.get(0);
        if (!access.owner().equals(requester)) throw new SecurityException("Only the document owner may change graph contributions");
        return access;
    }
    Head head(ObjectKey key, boolean lock) {
        List<Head> rows = jdbc.query("SELECT * FROM " + key.kind().table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> mapHead(key.kind(), rs), key.id());
        return rows.isEmpty() ? null : rows.get(0);
    }
    private Head mapHead(Kind kind, ResultSet rs) throws SQLException {
        return new Head(new ObjectKey(kind, rs.getString("id")), rs.getString("scope_id"), rs.getString("label"),
            rs.getLong("source_version"), rs.getLong("build_revision"), rs.getLong("description_source_version"), rs.getString("projection_id"),
            rs.getString("description"), rs.getString("desired_config"), rs.getBoolean("deleted"), rs.getBoolean("queryable"));
    }
    void ensureObject(ObjectKey key, String scope, Fact fact, String config) {
        String source = fact.kind() == Kind.RELATION ? hash(scope, Fact.entity(fact.source(), "endpoint").identity()) : null;
        String target = fact.kind() == Kind.RELATION ? hash(scope, Fact.entity(fact.target(), "endpoint").identity()) : null;
        if (fact.kind() == Kind.RELATION && fact.direction() != Direction.DIRECTED
                && normalized(fact.source()).compareTo(normalized(fact.target())) > 0) {
            String swap = source; source = target; target = swap;
        }
        jdbc.update("INSERT INTO " + key.kind().table +
            " (id,scope_id,identity_key,label,source_entity_id,target_entity_id,predicate_label,direction_type,desired_config)" +
            " VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            key.id(), scope, fact.identity(), fact.kind() == Kind.ENTITY ? fact.name() : fact.predicate(),
            source, target, fact.predicate(), fact.direction() == null ? null : fact.direction().name(), config);
    }
    Set<ObjectKey> documentObjects(long documentId) {
        Set<ObjectKey> result = new TreeSet<>();
        for (Kind kind : Kind.values()) jdbc.query("SELECT DISTINCT object_id FROM " + kind.sourceTable + " WHERE document_id=?",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.add(new ObjectKey(kind, rs.getString(1))), documentId);
        return result;
    }
    String enqueue(WorkType type, ObjectKey key, long version, String config, String requester, String payload, String discriminator) {
        long buildRevision = head(key, false).buildRevision();
        return enqueueTask(type,key,version,buildRevision,config,requester,payload,discriminator);
    }
    public String enqueueTask(WorkType type, ObjectKey key, long version, long buildRevision, String config,
            String requester, String payload, String discriminator) {
        String dedup = hash(type.name(), key.kind().name(), key.id(), Long.toString(version), Long.toString(buildRevision), config, discriminator);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO kg_work (id,dedup_key,work_type,object_kind,object_id,source_version,build_revision,config_version,requester_id,payload_json)" +
            " VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            id, dedup, type.name(), key.kind().name(), key.id(), version, buildRevision, config, requester, payload);
        String actual = jdbc.queryForObject("SELECT id FROM kg_work WHERE dedup_key=?", String.class, dedup);
        if (id.equals(actual)) outbox(id, 0);
        return actual;
    }
    void outbox(String jobId, int delaySeconds) {
        jdbc.update("INSERT INTO kg_outbox (id,job_id,next_attempt_at) VALUES (?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)))",
            UUID.randomUUID().toString(), jobId, delaySeconds);
    }
    public Claim claim(String jobId) {
        return transaction(() -> {
            String token = UUID.randomUUID().toString();
            int count = jdbc.update("UPDATE kg_work SET work_status='RUNNING',attempts=attempts+1,lease_token=?," +
                "lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE id=? AND attempts<? AND " +
                "(work_status='PENDING' OR (work_status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6)))",
                token, properties.getLeaseSeconds(), jobId, Math.min(5,properties.getMaxAttempts()));
            if (count != 1) return null;
            return jdbc.queryForObject("SELECT * FROM kg_work WHERE id=?", (rs,n) -> new Claim(jobId,
                WorkType.valueOf(rs.getString("work_type")), new ObjectKey(Kind.valueOf(rs.getString("object_kind")), rs.getString("object_id")),
                rs.getLong("source_version"), rs.getLong("build_revision"), rs.getString("config_version"), rs.getString("requester_id"), token, rs.getString("payload_json")), jobId);
        });
    }
    public boolean renew(Claim claim) {
        return jdbc.update("UPDATE kg_work SET lease_until=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)) WHERE id=? AND lease_token=? AND work_status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)",
            properties.getLeaseSeconds(),claim.jobId(),claim.token())==1;
    }
    public boolean owns(Claim claim) {
        return !jdbc.queryForList("SELECT id FROM kg_work WHERE id=? AND lease_token=? AND work_status='RUNNING' " +
            "AND lease_until>CURRENT_TIMESTAMP(6) FOR UPDATE", String.class, claim.jobId(), claim.token()).isEmpty();
    }
    boolean matches(Head head, Claim claim) {
        return head != null && head.sourceVersion() == claim.sourceVersion() && head.buildRevision() == claim.buildRevision()
            && head.desiredConfig().equals(claim.configVersion());
    }
    public void finish(Claim claim, String status) {
        jdbc.update("UPDATE kg_work SET work_status=?,lease_token=NULL,lease_until=NULL,last_error=NULL WHERE id=? AND lease_token=?",
            status, claim.jobId(), claim.token());
    }
    public Snapshot snapshot(Claim claim) {
        return snapshot.execute(status -> {
            Head head = head(claim.key(), false);
            if (!matches(head, claim) || head.deleted()) return null;
            List<String> facts = jdbc.queryForList("SELECT description FROM " + claim.key().kind().sourceTable +
                " WHERE object_id=? ORDER BY id", String.class, claim.key().id());
            return new Snapshot(head, facts);
        });
    }
    public boolean accept(Claim claim, String description) {
        required(description);
        return transaction(() -> {
            if (!owns(claim)) return false;
            Head head = head(claim.key(), true);
            if (!matches(head, claim) || head.deleted() || !sourcesAuthorized(head)) {
                finish(claim, "SUPERSEDED"); return false;
            }
            String projectionId = UUID.randomUUID().toString();
            jdbc.update("UPDATE " + claim.key().kind().table +
                " SET description=?,description_source_version=?,projection_id=?,queryable=FALSE WHERE id=?",
                description, claim.sourceVersion(), projectionId, claim.key().id());
            Projection projection = new Projection(claim.key(), head.scopeId(), head.label(), claim.sourceVersion(), claim.buildRevision(),
                projectionId, description, claim.configVersion());
            enqueue(WorkType.SYNC, claim.key(), claim.sourceVersion(), claim.configVersion(), claim.requesterId(),
                json(projection), projectionId);
            finish(claim, "SUCCEEDED");
            return true;
        });
    }
    public boolean publish(Claim claim, Projection projection) {
        return transaction(() -> {
            if (!owns(claim)) return false;
            Head head = head(claim.key(), true);
            if (!matches(head, claim) || head.deleted() || !Objects.equals(head.projectionId(), projection.projectionId())
                    || head.descriptionSourceVersion() != head.sourceVersion() || !sourcesAuthorized(head)) {
                finish(claim, "SUPERSEDED"); return false;
            }
            jdbc.update("UPDATE " + claim.key().kind().table + " SET indexed_projection_id=?,queryable=TRUE WHERE id=?",
                projection.projectionId(), claim.key().id());
            finish(claim, "SUCCEEDED");
            return true;
        });
    }
    public boolean current(Claim claim, String projectionId, boolean deleted) {
        Head head = head(claim.key(), false);
        return matches(head, claim) && head.deleted() == deleted && (projectionId == null || projectionId.equals(head.projectionId()));
    }
    public void complete(Claim claim, String status) {
        transaction(() -> { if (owns(claim)) finish(claim, status); return null; });
    }
    public void failed(Claim claim, Exception error) {
        transaction(() -> {
            if (!owns(claim)) return null;
            Integer attempts = jdbc.queryForObject("SELECT attempts FROM kg_work WHERE id=?", Integer.class, claim.jobId());
            boolean permanent=error instanceof IllegalArgumentException || error instanceof com.fasterxml.jackson.core.JsonProcessingException
                || error instanceof com.yizhaoqi.smartpai.service.LlmResponseValidator.Failure && !com.yizhaoqi.smartpai.service.LlmResponseValidator.retryable(error)
                || error instanceof org.springframework.web.reactive.function.client.WebClientResponseException && !com.yizhaoqi.smartpai.service.LlmResponseValidator.retryable(error);
            boolean retry = !permanent && attempts != null && attempts < Math.min(5,properties.getMaxAttempts());
            jdbc.update("UPDATE kg_work SET work_status=?,last_error=?,lease_token=NULL,lease_until=NULL WHERE id=?",
                retry ? "PENDING" : "FAILED", abbreviated(error), claim.jobId());
            if (retry) outbox(claim.jobId(), Math.min(60, 1 << Math.min(attempts, 5)));
            return null;
        });
    }
    public void recoverExpired() {
        List<String> ids = jdbc.queryForList("SELECT id FROM kg_work WHERE work_status='RUNNING' AND lease_until<CURRENT_TIMESTAMP(6) LIMIT ?",
            String.class, properties.getDispatchBatchSize());
        for (String id : ids) transaction(() -> {
            List<Integer> counts = jdbc.query("SELECT attempts FROM kg_work WHERE id=? AND work_status='RUNNING' " +
                "AND lease_until<CURRENT_TIMESTAMP(6) FOR UPDATE", (rs,n) -> rs.getInt(1), id);
            if (counts.isEmpty()) return null;
            boolean retry = counts.get(0) < Math.min(5,properties.getMaxAttempts());
            jdbc.update("UPDATE kg_work SET work_status=?,lease_token=NULL,lease_until=NULL,last_error='Worker lease expired' WHERE id=?",
                retry ? "PENDING" : "FAILED", id);
            if (retry) outbox(id, 0);
            return null;
        });
        List<String> pending = jdbc.queryForList("SELECT id FROM kg_work WHERE work_status='PENDING' AND " +
            "(last_dispatch_at IS NULL OR last_dispatch_at<TIMESTAMPADD(SECOND,-60,CURRENT_TIMESTAMP(6))) LIMIT ?",
            String.class, properties.getDispatchBatchSize());
        for (String id : pending) transaction(() -> {
            List<String> locked = jdbc.queryForList("SELECT id FROM kg_work WHERE id=? AND work_status='PENDING' FOR UPDATE", String.class, id);
            if (locked.isEmpty()) return null;
            Long queued = jdbc.queryForObject("SELECT COUNT(*) FROM kg_outbox WHERE job_id=? AND published=FALSE", Long.class, id);
            if (queued != null && queued == 0) {
                outbox(id, 0);
                jdbc.update("UPDATE kg_work SET last_dispatch_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
            }
            return null;
        });
    }
    public Head published(ObjectKey key) {
        return snapshot.execute(status -> {
            Head head = head(key, false);
            if (head == null || head.deleted() || !head.queryable() || head.sourceVersion() != head.descriptionSourceVersion())
                return null;
            String indexed = jdbc.queryForObject("SELECT indexed_projection_id FROM " + key.kind().table + " WHERE id=?", String.class, key.id());
            return Objects.equals(indexed, head.projectionId()) && sourcesAuthorized(head) ? head : null;
        });
    }
    boolean sourcesAuthorized(Head head) {
        // A stale ES payload or an out-of-band file deletion must never resurrect a graph description.
        List<String> scopes = jdbc.query("SELECT f.user_id,f.org_tag,f.is_public,d.deleted,d.document_id FROM " + head.key().kind().sourceTable +
            " s LEFT JOIN file_upload f ON f.id=s.document_id LEFT JOIN kg_document_state d ON d.document_id=s.document_id WHERE s.object_id=?",
            (rs,n) -> rs.getString(1) == null || rs.getObject(5) == null || rs.getBoolean(4) ? "" : scope(rs.getString(1), rs.getString(2), rs.getBoolean(3)), head.key().id());
        return !scopes.isEmpty() && scopes.stream().allMatch(head.scopeId()::equals);
    }
    public List<Map<String,Object>> pendingEvents() {
        return jdbc.queryForList("SELECT id,job_id FROM kg_outbox WHERE published=FALSE AND next_attempt_at<=CURRENT_TIMESTAMP(6) ORDER BY next_attempt_at,id LIMIT ?",
            properties.getDispatchBatchSize());
    }
    public void delivered(String id) {
        transaction(() -> {
            jdbc.update("UPDATE kg_outbox SET published=TRUE,last_error=NULL WHERE id=?", id);
            jdbc.update("UPDATE kg_work SET last_dispatch_at=CURRENT_TIMESTAMP(6) WHERE id=(SELECT job_id FROM kg_outbox WHERE id=?)", id);
            return null;
        });
    }
    public boolean retryFailed(String jobId, String requesterId) {
        return transaction(() -> {
            int updated = jdbc.update("UPDATE kg_work SET work_status='PENDING',attempts=0,last_error=NULL " +
                "WHERE id=? AND requester_id=? AND work_status='FAILED'", jobId, requesterId);
            if (updated == 1) outbox(jobId, 0);
            return updated == 1;
        });
    }
    public void deliveryFailed(String id, Exception error) {
        jdbc.update("UPDATE kg_outbox SET attempts=attempts+1,last_error=?,next_attempt_at=TIMESTAMPADD(SECOND,5,CURRENT_TIMESTAMP(6)) WHERE id=? AND published=FALSE",
            abbreviated(error), id);
    }
    static String abbreviated(Exception error) {
        String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return text.substring(0, Math.min(2000, text.length()));
    }
}

