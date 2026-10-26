package com.yizhaoqi.smartpai.service.knowledge.graph;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.MultiEmbeddingClient;
import com.yizhaoqi.smartpai.config.GraphProtocolProperties;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="KNOWLEDGE_TEST_ES_URL",matches="http://127\\.0\\.0\\.1:19200")
class IndexSyncIntegrationTest {
    ElasticsearchClient client;RestClientTransport transport;KnowledgeSearchIndexService index;MultiEmbeddingClient embeddings;
    String prefix;List<String> created=new ArrayList<>();
    @BeforeEach void setup() {
        transport=new RestClientTransport(RestClient.builder(HttpHost.create(System.getenv("KNOWLEDGE_TEST_ES_URL"))).build(),new JacksonJsonpMapper());
        client=new ElasticsearchClient(transport);prefix="knowledge_it_"+UUID.randomUUID().toString().replace("-","");
        var properties=new GraphProtocolProperties();properties.setIndexPrefix(prefix);
        embeddings=mock(MultiEmbeddingClient.class);when(embeddings.currentModelVersion()).thenReturn("test-model:3");
        when(embeddings.embedWithUsage(anyList(),anyString(),any())).thenAnswer(i->new MultiEmbeddingClient.EmbeddingUsageResult(List.of(new float[]{1,0,0}),1,"test-model:3"));
        index=new KnowledgeSearchIndexService(client,embeddings,new ObjectMapper(),properties);
    }
    @AfterEach void cleanup() throws Exception { if(client!=null) for(String name:created) client.indices().delete(d->d.index(name).ignoreUnavailable(true));if(transport!=null) transport.close(); }
    Chunk chunk(String key,String text) { return new Chunk(key,"TEXT_CHUNK",text,1,1,null,null,null,null,null,null,null,null); }
    @Test void knnPermissionAndDocumentFiltersApplyBeforeRankingAndWritesAreSearchVisible() throws Exception {
        String physical=index.chunkIndexName(prefix,"test-model:3");created.add(physical);
        for(int i=1;i<=3;i++) {
            var c=new BuildContext(i,10,1,"cfg",i==2?"restricted":"allowed","user");
            index.writeChunks(physical,c,List.of(chunk("c","matching text")),List.of(new float[]{1,0,0}),"test-model:3");
        }
        for(String mode:List.of("vector","keyword","hybrid")) {
            var hits=index.candidates(physical,"matching",mode,Set.of("allowed"),List.of(1L),10,"user");
            assertEquals(1,hits.size(),mode);assertEquals(1L,((Number)hits.get(0).get("documentId")).longValue());
        }
        assertTrue(index.candidates(physical,"matching","vector",Set.of("none"),List.of(),10,"user").isEmpty());
    }
    @Test void immutableProjectionIdsAndVersionLimitedDeleteProtectNewerWrites() throws Exception {
        var key=new ObjectKey(Kind.ENTITY,"object");var old=new Projection(key,"scope","Entity",1,1,"old","old source","cfg");
        var current=new Projection(key,"scope","Entity",2,2,"new","new source","cfg");
        created.add(index.graphIndex(Kind.ENTITY,false));
        index.write(current,"user");index.write(old,"user");
        assertEquals(2,index.candidates(index.graphIndex(Kind.ENTITY,false),"source","keyword",Set.of("scope"),List.of(),10,"user").size());
        index.deleteThrough(key,1);
        var hits=index.candidates(index.graphIndex(Kind.ENTITY,false),"source","keyword",Set.of("scope"),List.of(),10,"user");
        assertEquals(1,hits.size());assertEquals("new",hits.get(0).get("projectionId"));
        index.discard(old);assertEquals(1,client.count(c->c.index(index.graphIndex(Kind.ENTITY,false))).count());
    }
    @Test void partialBulkFailureCannotBeReportedAsSuccessfulAndRetryIsIdempotent() throws Exception {
        String physical=index.chunkIndexName(prefix,"test-model:3");created.add(physical);
        var context=new BuildContext(1,1,1,"cfg","scope","user");
        assertThrows(IllegalStateException.class,()->index.writeChunks(physical,context,
            List.of(chunk("ok","text"),chunk("x".repeat(40000),"bad keyword length")),List.of(new float[]{1,0,0},new float[]{1,0,0}),"test-model:3"));
        index.writeChunks(physical,context,List.of(chunk("ok","text")),List.of(new float[]{1,0,0}),"test-model:3");
        assertEquals(1,client.count(c->c.index(physical)).count());
    }
    @Test void chunkCleanupCannotDeleteAnotherDocumentOrANewerGeneration() throws Exception {
        String physical=index.chunkIndexName(prefix,"test-model:3");created.add(physical);
        for(var context:List.of(new BuildContext(1,1,1,"cfg","scope","user"),new BuildContext(1,1,2,"cfg","scope","user"),new BuildContext(2,1,1,"cfg","scope","user")))
            index.writeChunks(physical,context,List.of(chunk("c","source")),List.of(new float[]{1,0,0}),"test-model:3");
        index.deleteChunksThrough(prefix,1,1);
        var hits=index.candidates(physical,"source","keyword",Set.of("scope"),List.of(),10,"user");
        assertEquals(2,hits.size());
        assertTrue(hits.stream().allMatch(h->((Number)h.get("documentId")).longValue()==2 || ((Number)h.get("buildRevision")).longValue()==2));
    }
    @Test void missingLegacyMultimodalIndexDoesNotBlockUploadScopedDeletion() {
        var legacy=new com.yizhaoqi.smartpai.service.MultimodalElasticsearchService(client);
        org.springframework.test.util.ReflectionTestUtils.setField(legacy,"indexName",prefix+"_absent");
        assertDoesNotThrow(()->legacy.deleteByFileMd5AndUserId("md5","user"));
        assertDoesNotThrow(()->legacy.deleteByFileMd5("md5"));
    }
}
