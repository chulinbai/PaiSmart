package com.yizhaoqi.smartpai.service.knowledge.graph;

import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

public interface GraphDescriptionModel {
    String configVersion();
    String describe(Snapshot snapshot, String requesterId, String expectedConfig);
}

