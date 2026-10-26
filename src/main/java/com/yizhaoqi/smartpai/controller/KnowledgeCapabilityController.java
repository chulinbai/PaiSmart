package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeCapabilityController {
    private final KnowledgeRagProperties properties;
    public KnowledgeCapabilityController(KnowledgeRagProperties properties) { this.properties=properties; }
    @GetMapping("/capabilities") public Map<String,Object> capabilities() { return Map.of("code",200,"data",Map.of("enabled",properties.isEnabled())); }
}
