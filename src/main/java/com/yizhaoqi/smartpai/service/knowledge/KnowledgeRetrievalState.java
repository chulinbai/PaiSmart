package com.yizhaoqi.smartpai.service.knowledge;

import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

public final class KnowledgeRetrievalState {
    public final String generationId;
    public final EvidencePool evidence=new EvidencePool();
    public final CitationRegistry citations=new CitationRegistry();
    public final Map<String,RetrievalResult> cache=new java.util.concurrent.ConcurrentHashMap<>();
    public KnowledgeRetrievalState(String generationId) { this.generationId=Objects.requireNonNull(generationId); }
}
