package com.yizhaoqi.smartpai.service.knowledge.graph;

import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

public interface GraphSearchIndex {
    /** Returns only after this exact immutable projection is visible to search. */
    void write(Projection projection, String requesterId) throws Exception;
    void deleteThrough(ObjectKey key, long sourceVersion) throws Exception;
    void discard(Projection projection) throws Exception;
}

