package com.yizhaoqi.smartpai.service.knowledge.graph;

import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.knowledge.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgePipelineIntegrationTest extends GraphConcurrencyIntegrationTest {
    KnowledgeIndexOrchestrator orchestrator;KnowledgeArtifactService artifacts;KnowledgeChunkService chunks;
    FileUpload file;KnowledgeIndexBuilder chunkBuilder,graphBuilder;
    KnowledgeSearchIndexService searchIndex;
    @BeforeEach void preparePipeline() {
        for(var column:Map.of("file_md5","VARCHAR(32)","file_name","VARCHAR(255)","vectorization_status","VARCHAR(32)","vectorization_error_message","VARCHAR(1000)","actual_embedding_tokens","BIGINT","actual_chunk_count","INT").entrySet()) {
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='file_upload' AND column_name=?",Integer.class,column.getKey());
            if(count==0) jdbc.execute("ALTER TABLE file_upload ADD COLUMN "+column.getKey()+" "+column.getValue());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-orchestration.sql")).execute(jdbc.getDataSource());
        for(String table:List.of("knowledge_chunk","knowledge_chunk_set","knowledge_index_state","knowledge_artifact","knowledge_document","kg_scope")) jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE file_upload SET file_md5='same-md5',file_name='test.pdf'");
        file=new FileUpload();file.setId(1L);file.setUserId("owner1");file.setOrgTag("DEFAULT");file.setPublic(true);file.setFileMd5("same-md5");file.setFileName("test.pdf");
        var access=mock(KnowledgeAccessService.class);when(access.resolve("owner1")).thenReturn(new Access("owner1",Set.of("DEFAULT")));
        when(access.requireOwner(eq(1L),any())).thenReturn(file);when(access.scope(any())).thenAnswer(i->{FileUpload f=i.getArgument(0);return GraphStore.scope(f.getUserId(),f.getOrgTag(),f.isPublic());});
        when(access.sameScope(any(),anyString())).thenReturn(true);
        var files=mock(FileUploadRepository.class);when(files.findById(1L)).thenReturn(Optional.of(file));
        chunks=new KnowledgeChunkService(jdbc,store,new KnowledgeRagProperties());
        chunkBuilder=mock(KnowledgeIndexBuilder.class);when(chunkBuilder.type()).thenReturn(IndexType.CHUNK);when(chunkBuilder.configVersion()).thenReturn("chunk-config");
        graphBuilder=new KnowledgeIndexBuilder() {
            public IndexType type(){return IndexType.GRAPH;}public Set<String> capabilities(){return Set.of("hybrid");}public String configVersion(){return "graph-config";}
            public BuildResult build(BuildContext c,ChunkSet set,FileUpload f) { return new BuildResult("graph",1,0,new DocumentBatch(f.getId(),c.buildRevision(),List.of(new ChunkExtraction(set.id()+":text:1","Shared entity","extract-v1",List.of(Fact.entity("Shared","graph source")))))); }
        };
        searchIndex=mock(KnowledgeSearchIndexService.class);
        orchestrator=new KnowledgeIndexOrchestrator(List.of(chunkBuilder,graphBuilder),store,jdbc,chunks,new KnowledgeIndexTaskService(store),access,files,contributions,searchIndex,new KnowledgeRagProperties());
        artifacts=new KnowledgeArtifactService(jdbc,store,List.of(chunkBuilder,graphBuilder));
    }
    List<Chunk> sources() { return List.of(new Chunk("text:1","TEXT_CHUNK","Shared entity",1,2,"Shared",null,null,null,null,null,null,null)); }
    @Test void modelChangeRequiresRebuildButKeywordGenerationRemainsUsable() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");
        when(chunkBuilder.build(any(),any(),any())).thenReturn(new BuildResult("chunks-model-a",1,10));
        orchestrator.execute(claim("BUILD_CHUNK"));
        assertEquals("READY",artifacts.state(1,IndexType.CHUNK).get("phase"));
        when(chunkBuilder.configVersion()).thenReturn("model-b");
        assertEquals("NOT_READY",artifacts.state(1,IndexType.CHUNK).get("phase"));
        assertEquals("READY",artifacts.state(1,IndexType.CHUNK,"keyword").get("phase"));
        assertEquals(0,artifacts.coverage(List.of(file),IndexType.CHUNK).ready());
    }
    @Test void accessChangeRevokesPublishedGraphAndSchedulesBothNewScopesAtomically() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");orchestrator.execute(claim("BUILD_GRAPH"));drainGraph();
        var old=new ObjectKey(Kind.ENTITY,jdbc.queryForObject("SELECT id FROM kg_entity WHERE label='Shared'",String.class));
        assertNotNull(store.published(old));
        var jobs=orchestrator.updateAccess(1,null,false,"owner1");
        assertEquals(2,jobs.size());assertNull(store.published(old));
        assertFalse(jdbc.queryForObject("SELECT is_public FROM file_upload WHERE id=1",Boolean.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_index_state WHERE document_id=1 AND phase='PENDING' AND build_revision=2",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM kg_work WHERE work_type='DELETE_CHUNKS'",Integer.class));
    }
    @Test void withdrawnChunkCleanupIsDurableAndBoundedByDocumentAndGeneration() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");
        orchestrator.withdraw(1,"owner1");
        var cleanup=claim("DELETE_CHUNKS");orchestrator.execute(cleanup);
        verify(searchIndex).deleteChunksThrough("knowledge_chunks",1L,1L);
        assertEquals("REVOKED",artifacts.state(1,IndexType.CHUNK).get("phase"));
    }
    void drainGraph() throws Exception {
        var merge=new GraphMergeService(store,model);var sync=new IndexSyncWorker(store,new GraphSearchIndex(){public void write(Projection p,String u){}public void deleteThrough(ObjectKey k,long v){}public void discard(Projection p){}},model);
        for(int i=0;i<10;i++) {
            var ids=jdbc.queryForList("SELECT id FROM kg_work WHERE work_status='PENDING' AND work_type IN ('MERGE','SYNC','DELETE')",String.class);
            if(ids.isEmpty()) return;
            for(String id:ids) { var c=store.claim(id);if(c.type()==WorkType.MERGE) merge.merge(c);else sync.sync(c); }
        }
    }
    @Test void snapshotAndOutboxCommitTogetherAndDuplicateDeliveryDoesNotDuplicateBuilds() {
        var ids=orchestrator.acceptSources(1,sources(),"owner1");var duplicate=orchestrator.acceptSources(1,sources(),"owner1");
        assertEquals(ids,duplicate);assertEquals(2,ids.size());assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kg_outbox",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk_set WHERE sealed=TRUE",Integer.class));
        assertEquals("PENDING",artifacts.state(1,IndexType.CHUNK).get("phase"));
        for(var event:store.pendingEvents()) store.delivered((String)event.get("id"));
        assertEquals("PENDING",artifacts.state(1,IndexType.CHUNK).get("phase"),"Kafka delivery must not mark search READY");
    }
    @Test void graphBuildPublishesWhenChunkEmbeddingFails() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");
        when(chunkBuilder.build(any(),any(),any())).thenThrow(new IllegalStateException("embedding unavailable"));
        for(int i=0;i<5;i++) { var claim=claim("BUILD_CHUNK");try{orchestrator.execute(claim);}catch(Exception error){store.failed(claim,error);} }
        orchestrator.execute(claim("BUILD_GRAPH"));assertEquals("SYNCING",artifacts.state(1,IndexType.GRAPH).get("phase"));
        drainGraph();assertEquals("READY",artifacts.state(1,IndexType.GRAPH).get("phase"));assertEquals("FAILED",artifacts.state(1,IndexType.CHUNK).get("phase"));
    }
    @Test void staleBuildCannotPublishOverNewerRequestedGeneration() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");var old=claim("BUILD_CHUNK");
        when(chunkBuilder.build(any(),any(),any())).thenAnswer(i->{orchestrator.rebuild(1,Set.of(IndexType.CHUNK),"owner1");return new BuildResult("test_index",1,5);});
        orchestrator.execute(old);
        assertEquals("SUPERSEDED",jdbc.queryForObject("SELECT work_status FROM kg_work WHERE id=?",String.class,old.jobId()));
        assertEquals("PENDING",artifacts.state(1,IndexType.CHUNK).get("phase"));
        assertNull(artifacts.state(1,IndexType.CHUNK).get("ready_chunk_set"));
    }
    @Test void losingLeaseDoesNotDeleteTheReplacementWorkersSameGeneration() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");var old=claim("BUILD_CHUNK");
        when(chunkBuilder.build(any(),any(),any())).thenAnswer(i->{
            jdbc.update("UPDATE kg_work SET lease_until=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",old.jobId());
            var replacement=store.claim(old.jobId());assertNotNull(replacement);
            jdbc.update("UPDATE knowledge_index_state SET phase='READY',ready_chunk_set=target_chunk_set,ready_build_revision=build_revision WHERE document_id=1 AND index_type='CHUNK'");
            store.complete(replacement,"SUCCEEDED");return new BuildResult("test_index",1,5);
        });
        orchestrator.execute(old);
        assertEquals("READY",artifacts.state(1,IndexType.CHUNK).get("phase"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM kg_work WHERE work_type='DELETE_CHUNKS'",Integer.class));
    }
    @Test void deletionDuringExternalBuildCannotPublishAndSchedulesLateWriteCleanup() throws Exception {
        orchestrator.acceptSources(1,sources(),"owner1");var old=claim("BUILD_CHUNK");
        when(chunkBuilder.build(any(),any(),any())).thenAnswer(i->{
            orchestrator.withdraw(1,"owner1");jdbc.update("DELETE FROM file_upload WHERE id=1");return new BuildResult("test_index",1,5);
        });
        orchestrator.execute(old);
        assertEquals("REVOKED",artifacts.state(1,IndexType.CHUNK).get("phase"));
        assertEquals("SUPERSEDED",jdbc.queryForObject("SELECT work_status FROM kg_work WHERE id=?",String.class,old.jobId()));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM kg_work WHERE work_type='DELETE_CHUNKS'",Integer.class));
    }
}
