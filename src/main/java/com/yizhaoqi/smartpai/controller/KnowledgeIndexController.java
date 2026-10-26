package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.knowledge.*;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UploadService;
import com.yizhaoqi.smartpai.service.FileTypeValidationService;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;

@RestController
@RequestMapping("/api/v1/knowledge")
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeIndexController {
    private final KnowledgeAccessService access;private final KnowledgeArtifactService artifacts;
    private final KnowledgeIndexOrchestrator indexes;private final KnowledgeSourceAssembler sources;
    private final ParseService parser;private final UploadService uploads;private final FileTypeValidationService fileTypes;private final DocumentVectorRepository vectors;
    public KnowledgeIndexController(KnowledgeAccessService access,KnowledgeArtifactService artifacts,KnowledgeIndexOrchestrator indexes,
            KnowledgeSourceAssembler sources,ParseService parser,UploadService uploads,FileTypeValidationService fileTypes,DocumentVectorRepository vectors) {
        this.access=access;this.artifacts=artifacts;this.indexes=indexes;this.sources=sources;this.parser=parser;this.uploads=uploads;this.fileTypes=fileTypes;this.vectors=vectors;
    }
    @GetMapping("/documents/{id}/indexes")
    public Map<String,Object> states(@PathVariable long id,@RequestAttribute("userId") String user) {
        access.requireRead(id,access.resolve(user));return ok(artifacts.states(id));
    }
    @GetMapping("/artifacts")
    public Map<String,Object> artifacts(@RequestAttribute("userId") String user) {
        var docs=access.documents(access.resolve(user),List.of());var scopes=docs.stream().map(access::scope).collect(Collectors.toSet());
        return ok(Map.of("artifacts",artifacts.artifacts(scopes),"chunkCoverage",artifacts.coverage(docs,IndexType.CHUNK),"graphCoverage",artifacts.coverage(docs,IndexType.GRAPH)));
    }
    public record RebuildRequest(Set<IndexType> indexTypes) {}
    public record AccessRequest(String orgTag,boolean isPublic) {}
    @PutMapping("/documents/{id}/access")
    public ResponseEntity<?> updateAccess(@PathVariable long id,@RequestBody AccessRequest request,@RequestAttribute("userId") String user) {
        return ResponseEntity.accepted().body(ok(Map.of("jobIds",indexes.updateAccess(id,request.orgTag(),request.isPublic(),user))));
    }
    @PostMapping("/documents/{id}/indexes/rebuild")
    public ResponseEntity<?> rebuild(@PathVariable long id,@RequestBody RebuildRequest request,@RequestAttribute("userId") String user) {
        return ResponseEntity.accepted().body(ok(Map.of("jobIds",indexes.rebuild(id,request.indexTypes(),user))));
    }
    @PostMapping("/documents/{id}/indexes/backfill")
    public ResponseEntity<?> backfill(@PathVariable long id,@RequestAttribute("userId") String user) throws Exception {
        var file=access.requireOwner(id,access.resolve(user));
        if(!fileTypes.isSupportedImageFileName(file.getFileName()) && vectors.countByFileMd5(file.getFileMd5())==0)
            try(var stream=uploads.getMergedFileStream(file.getFileMd5())) { parser.parseAndSave(file.getFileMd5(),stream,file.getUserId(),file.getOrgTag(),file.isPublic()); }
        return ResponseEntity.accepted().body(ok(Map.of("jobIds",indexes.acceptSources(id,sources.assemble(file),user))));
    }
    @ExceptionHandler(SecurityException.class) ResponseEntity<?> forbidden(SecurityException e) { return ResponseEntity.status(403).body(Map.of("code",403,"message",e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) ResponseEntity<?> invalid(RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("code",400,"message",e.getMessage())); }
    private Map<String,Object> ok(Object data) { return Map.of("code",200,"data",data); }
}
