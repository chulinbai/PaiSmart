package com.yizhaoqi.smartpai.service.knowledge;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceAssemblerTest {
    SourceReference source(long doc,String key) {
        return SourceReference.of(doc,10,new Chunk(key,"TEXT_CHUNK","原文",1,3,"anchor",null,null,null,null,null,null,null),"same-md5","test.pdf");
    }
    Evidence evidence(String id,String type,SourceReference source,int rank) { return new Evidence(id,type,"内容",1,"test",rank,List.of(source),Map.of()); }
    @Test void sourceIdentityIncludesUploadButSharedChunkReusesItsCitation() {
        var source=source(1,"c");var registry=new CitationRegistry();
        assertEquals(1,registry.register(source));assertEquals(1,registry.register(source));
        assertEquals(2,registry.register(source(2,"c")));
        var pool=new EvidencePool();pool.add(List.of(evidence("chunk","TEXT_CHUNK",source,1),evidence("relation","GRAPH_RELATION",source,1)));
        assertEquals(2,pool.values().size());
        var assembly=new EvidenceAssembler().assemble(pool.ranked(),registry,2000);
        assertTrue(assembly.text().contains("GRAPH_RELATION"));assertTrue(assembly.text().contains("TEXT_CHUNK"));
        assertEquals(1,assembly.sources().size());assertEquals(2,registry.sources().size());
    }
    @Test void pruningNeverRenumbersAnExistingReferenceAndPreservesPreviewLocation() {
        var first=source(1,"a");var second=source(1,"b");var registry=new CitationRegistry();registry.register(first);registry.register(second);
        var assembled=new EvidenceAssembler().assemble(List.of(evidence("second","TEXT_CHUNK",second,1)),registry,1000);
        assertTrue(assembled.text().contains("[2]"));assertFalse(assembled.text().contains("[1]"));
        assertEquals(3,assembled.sources().get(0).getPageNumber());assertEquals(second.sourceId(),assembled.sources().get(0).getSourceId());
        assertEquals(2,registry.sources().size());
    }
    @Test void rankFusionDoesNotUseIncomparableEsScoresAndDeduplicatesEvidence() {
        var pool=new EvidencePool();var a=evidence("a","TEXT_CHUNK",source(1,"a"),1);var b=evidence("b","GRAPH_ENTITY",source(1,"b"),2);
        pool.add(List.of(b,a));pool.add(List.of(b));assertEquals(2,pool.values().size());assertEquals("b",pool.ranked().get(0).evidenceId());
    }
}
