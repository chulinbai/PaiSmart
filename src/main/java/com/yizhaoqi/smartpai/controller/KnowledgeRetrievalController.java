package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.knowledge.KnowledgeRetrievalService;
import com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.RetrievalRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge")
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeRetrievalController {
    private final KnowledgeRetrievalService retrieval;
    public KnowledgeRetrievalController(KnowledgeRetrievalService retrieval) { this.retrieval=retrieval; }
    @PostMapping("/retrieve") public Map<String,Object> retrieve(@RequestBody RetrievalRequest request,@RequestAttribute("userId") String user) {
        return Map.of("code",200,"data",retrieval.retrieve(request,user));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> forbidden(SecurityException e) { return ResponseEntity.status(403).body(Map.of("code",403,"message",e.getMessage())); }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage())); }
}
