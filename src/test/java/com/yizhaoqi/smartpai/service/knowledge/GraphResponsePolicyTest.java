package com.yizhaoqi.smartpai.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.service.knowledge.graph.GraphStore;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GraphResponsePolicyTest {
    final LlmProviderRouter router=mock(LlmProviderRouter.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final ObjectMapper mapper=new ObjectMapper();
    final ModelProviderConfigService.ActiveProviderView provider=new ModelProviderConfigService.ActiveProviderView("test","test","openai-compatible","http://localhost","test","",null,16000);
    final BuildContext context=new BuildContext(1,2,1,"config","scope","1");
    final Chunk chunk=new Chunk("c","TEXT_CHUNK","source",1,null,null,null,null,null,null,null,null,null);
    final AtomicReference<String> cache=new AtomicReference<>();
    FileUpload file;GraphExtractionService extraction;
    @BeforeEach void setup() {
        var providers=mock(ModelProviderConfigService.class);when(providers.getActiveProvider(anyString())).thenReturn(provider);
        when(router.activeProvider()).thenReturn(provider);
        var store=new GraphStore(jdbc,mapper,mock(org.springframework.transaction.PlatformTransactionManager.class),new com.yizhaoqi.smartpai.config.GraphProtocolProperties());
        when(jdbc.queryForList(anyString(),eq(String.class),anyString())).thenAnswer(i->cache.get()==null?List.of():List.of(cache.get()));
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(i->{Object[] args=i.getArguments();cache.set(String.valueOf(args[args.length-1]));return 1;});
        when(jdbc.queryForObject(anyString(),eq(String.class),anyString())).thenAnswer(i->cache.get());
        file=new FileUpload();file.setId(1L);file.setFileMd5("test");
        extraction=new GraphExtractionService(router,providers,new KnowledgeRagProperties(),jdbc,store,mapper,mock(KnowledgeSourceAssembler.class));
    }
    void response(String text,String reason) {
        when(router.completeReActTurnWithProvider(eq(provider),anyString(),anyList(),anyList(),anyInt(),any()))
            .thenReturn(new LlmProviderRouter.ReActTurn(text,List.of(),Map.of(),reason,1,1));
    }
    @Test void lengthIsClassifiedBeforeParsingAndNeverCached() {
        response("{broken","length");
        assertEquals(LlmResponseValidator.Kind.LENGTH,assertThrows(LlmResponseValidator.Failure.class,()->extraction.extract(context,file,chunk)).kind());assertNull(cache.get());
    }
    @Test void completeMalformedJsonIsSchemaFailureAndNeverCached() {
        response("{broken","stop");
        assertEquals(LlmResponseValidator.Kind.STRUCTURE,assertThrows(LlmResponseValidator.Failure.class,()->extraction.extract(context,file,chunk)).kind());assertNull(cache.get());
        response("{\"facts\":{}}","stop");assertThrows(LlmResponseValidator.Failure.class,()->extraction.extract(context,file,chunk));assertNull(cache.get());
    }
    @Test void numericNamesAreNotSilentlyCoercedToStrings() {
        response("{\"facts\":[{\"kind\":\"ENTITY\",\"name\":123,\"description\":\"fact\"}]}","stop");
        assertEquals(LlmResponseValidator.Kind.STRUCTURE,assertThrows(LlmResponseValidator.Failure.class,()->extraction.extract(context,file,chunk)).kind());assertNull(cache.get());
    }
    @Test void validEmptyFactsCanBeCachedAndReused() throws Exception {
        response("{\"facts\":[]}","stop");
        assertTrue(extraction.extract(context,file,chunk).facts().isEmpty());assertNotNull(cache.get());
        assertTrue(extraction.extract(context,file,chunk).facts().isEmpty());
        verify(router,times(1)).completeReActTurnWithProvider(eq(provider),anyString(),anyList(),anyList(),anyInt(),any());
    }
    @Test void keywordFailureFallsBackToOriginalQueryWithoutRetry() {
        response("{broken","stop");var keywords=new GraphKeywordExtractor(router,mapper).extract("原查询","1");
        assertEquals("原查询",keywords.entities());assertEquals("原查询",keywords.topics());
        verify(router,times(1)).completeReActTurnWithProvider(eq(provider),anyString(),anyList(),anyList(),anyInt(),any());
    }
}
