package com.yizhaoqi.smartpai.service.knowledge.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.GraphProtocolProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses an explicitly selected disposable MySQL database, never the application database. */
@EnabledIfEnvironmentVariable(named="GRAPH_TEST_MYSQL_URL", matches=".*paismart_graph_test.*")
class GraphConcurrencyIntegrationTest {
    JdbcTemplate jdbc;
    GraphStore store;
    GraphContributionService contributions;
    String config;
    GraphDescriptionModel model;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(System.getenv("GRAPH_TEST_MYSQL_URL"), "root", "graph-test-only");
        jdbc = new JdbcTemplate(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-graph.sql")).execute(ds);
        for (String table : List.of("kg_outbox", "kg_work", "kg_entity_source", "kg_relation_source",
                "kg_chunk_extraction", "kg_entity", "kg_relation", "kg_document_state")) jdbc.update("DELETE FROM " + table);
        jdbc.execute("CREATE TABLE IF NOT EXISTS file_upload (id BIGINT PRIMARY KEY,user_id VARCHAR(100),org_tag VARCHAR(100),is_public BOOLEAN)");
        jdbc.update("DELETE FROM file_upload");
        for (long id=1;id<=3;id++) jdbc.update("INSERT INTO file_upload(id,user_id,org_tag,is_public) VALUES (?,?,?,TRUE)",id,"owner"+id,"DEFAULT");
        config = "config-a";
        model = new GraphDescriptionModel() {
            public String configVersion() { return config; }
            public String describe(Snapshot snapshot,String requester,String expected) {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return String.join(";", snapshot.contributions());
            }
        };
        store = new GraphStore(jdbc, new ObjectMapper(), new DataSourceTransactionManager(ds), new GraphProtocolProperties());
        contributions = new GraphContributionService(store,model);
    }
    DocumentBatch batch(long id,long rev,String text) {
        return new DocumentBatch(id,rev,List.of(new ChunkExtraction("chunk-1",text,"extract-v1",List.of(Fact.entity("Shared",text)))));
    }
    ObjectKey key() { return new ObjectKey(Kind.ENTITY,hash(GraphStore.scope("owner1","DEFAULT",true),Fact.entity("Shared","x").identity())); }
    Claim claim(String type) {
        String id=jdbc.queryForObject("SELECT id FROM kg_work WHERE work_type=? AND work_status='PENDING' ORDER BY source_version DESC,build_revision DESC LIMIT 1",String.class,type);
        return store.claim(id);
    }
    void drain() throws Exception {
        var merge = new GraphMergeService(store,model);
        var sync = new IndexSyncWorker(store,new GraphSearchIndex() {
            public void write(Projection p,String requester) { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); }
            public void deleteThrough(ObjectKey k,long v) {}
            public void discard(Projection p) {}
        },model);
        for (int pass=0;pass<20;pass++) {
            var ids=jdbc.queryForList("SELECT id FROM kg_work WHERE work_status='PENDING'",String.class);
            if (ids.isEmpty()) return;
            for (String id:ids) { var c=store.claim(id); if(c==null) continue; if(c.type()==WorkType.MERGE) merge.merge(c); else sync.sync(c); }
        }
        fail("Tasks did not converge");
    }
    @Test void concurrentDocumentsRetainAllSourcesAndDeletingOneRebuildsTheRemainder() throws Exception {
        var start=new CountDownLatch(1); var executor=Executors.newFixedThreadPool(3);
        try {
            var futures=new ArrayList<Future<?>>();
            for(long id=1;id<=3;id++) { long doc=id; futures.add(executor.submit(()->{ start.await(); contributions.replace(batch(doc,1,"source-"+doc),"owner"+doc); return null; })); }
            start.countDown(); for(var future:futures) future.get(20,TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM kg_entity_source",Integer.class));
        assertEquals(3,store.head(key(),false).sourceVersion());
        drain(); var published=store.published(key()); assertNotNull(published);
        for(int i=1;i<=3;i++) assertTrue(published.description().contains("source-"+i));
        contributions.withdraw(2,"owner2"); assertNull(store.published(key())); drain();
        assertFalse(store.published(key()).description().contains("source-2"));
        assertTrue(store.published(key()).description().contains("source-1"));
        assertFalse(contributions.replace(batch(2,2,"resurrect"),"owner2").applied());
    }
    @Test void staleMergeAndExpiredWorkerCannotPublish() throws Exception {
        contributions.replace(batch(1,1,"original"),"owner1"); var stale=claim("MERGE");
        contributions.replace(batch(2,1,"addition"),"owner2"); assertFalse(store.accept(stale,"obsolete"));
        var current=claim("MERGE");
        jdbc.update("UPDATE kg_work SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",current.jobId());
        var takeover=store.claim(current.jobId()); assertNotNull(takeover);
        assertFalse(store.accept(current,"lost lease")); assertTrue(store.accept(takeover,"both sources"));
        assertFalse(store.accept(takeover,"duplicate"));
        drain(); assertEquals("both sources",store.published(key()).description());
    }
    @Test void duplicateAndConfigABAUseDistinctBuildGenerations() throws Exception {
        var input=batch(1,1,"same"); contributions.replace(input,"owner1"); drain();
        assertFalse(contributions.replace(input,"owner1").applied());
        config="config-b"; contributions.refresh(1,"owner1"); drain();
        config="config-a"; contributions.refresh(1,"owner1"); drain();
        assertEquals(1,store.published(key()).sourceVersion()); assertEquals(3,store.published(key()).buildRevision());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM kg_work WHERE work_type='MERGE'",Integer.class));
    }
    @Test void aclChangeRejectsPublishedPayloadEvenBeforeMaintenance() throws Exception {
        contributions.replace(batch(1,1,"private after change"),"owner1"); drain(); assertNotNull(store.published(key()));
        jdbc.update("UPDATE file_upload SET is_public=FALSE WHERE id=1"); assertNull(store.published(key()));
        contributions.refresh(1,"owner1"); drain(); assertNull(store.published(key()));
        var privateKey=new ObjectKey(Kind.ENTITY,hash(GraphStore.scope("owner1","DEFAULT",false),Fact.entity("Shared","x").identity()));
        assertNotNull(store.published(privateKey));
    }
    @Test void rollbackDoesNotCommitSourcesOrOutbox() {
        assertThrows(IllegalStateException.class,()->store.transaction(()->{ contributions.replace(batch(1,1,"rollback"),"owner1"); throw new IllegalStateException("abort"); }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kg_entity_source",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kg_outbox",Integer.class));
    }
    @Test void directionIsPartOfRelationshipIdentity() {
        var forward=new Fact(Kind.RELATION,null,"A","B","depends on",Direction.DIRECTED,"A requires B");
        var reverse=new Fact(Kind.RELATION,null,"B","A","depends on",Direction.DIRECTED,"B requires A");
        var unknown=new Fact(Kind.RELATION,null,"A","B","depends on",Direction.UNKNOWN,"uncertain");
        contributions.replace(new DocumentBatch(1,1,List.of(new ChunkExtraction("c","A and B","v1",List.of(forward,reverse,unknown)))),"owner1");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM kg_relation",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kg_entity",Integer.class));
    }
    @Test void fiveClaimsIncludeFirstAndDuplicateFailuresNeverIncrementAttempts() {
        contributions.replace(batch(1,1,"retry"),"owner1");
        String id=jdbc.queryForObject("SELECT id FROM kg_work WHERE work_type='MERGE'",String.class);
        for(int attempt=1;attempt<=5;attempt++) {
            Claim claimed=store.claim(id);assertNotNull(claimed);
            assertNull(store.claim(id));
            store.failed(claimed,new IllegalStateException("temporary provider failure"));
            store.failed(claimed,new IllegalStateException("duplicate callback"));
            assertEquals(attempt,jdbc.queryForObject("SELECT attempts FROM kg_work WHERE id=?",Integer.class,id));
        }
        assertNull(store.claim(id));
        assertEquals("FAILED",jdbc.queryForObject("SELECT work_status FROM kg_work WHERE id=?",String.class,id));
    }
    @Test void expiredLeaseRecoveryDoesNotCountAnAttemptAndOldFailureCannotOverwriteNewClaim() {
        contributions.replace(batch(1,1,"lease"),"owner1");var old=claim("MERGE");
        jdbc.update("UPDATE kg_work SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",old.jobId());
        store.recoverExpired();store.recoverExpired();
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM kg_work WHERE id=?",Integer.class,old.jobId()));
        var replacement=store.claim(old.jobId());assertNotNull(replacement);
        store.failed(old,new IllegalStateException("late failure"));
        assertEquals(replacement.token(),jdbc.queryForObject("SELECT lease_token FROM kg_work WHERE id=?",String.class,old.jobId()));
        assertEquals(2,jdbc.queryForObject("SELECT attempts FROM kg_work WHERE id=?",Integer.class,old.jobId()));
    }
}
