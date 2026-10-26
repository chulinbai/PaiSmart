package com.yizhaoqi.smartpai.service.knowledge;

import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

/** Append-only for one generation. Never renumber citations already seen by a model or client. */
public final class CitationRegistry {
    private final Map<String,Integer> numbers=new LinkedHashMap<>();
    private final Map<Integer,SourceReference> sources=new LinkedHashMap<>();
    public int register(SourceReference source) {
        Integer number=numbers.get(source.sourceId());if(number!=null) return number;
        int next=numbers.size()+1;numbers.put(source.sourceId(),next);sources.put(next,source);return next;
    }
    public Map<Integer,SourceReference> sources() { return Collections.unmodifiableMap(new LinkedHashMap<>(sources)); }
}
