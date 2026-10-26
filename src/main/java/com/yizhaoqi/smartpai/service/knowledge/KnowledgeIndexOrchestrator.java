package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.service.knowledge.graph.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

/** Coordinates builders; all model/ES calls occur before the fenced publication transaction. */
@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeIndexOrchestrator {
    private final Map<IndexType,KnowledgeIndexBuilder> builders=new EnumMap<>(IndexType.class);
    private final GraphStore store; private final JdbcTemplate jdbc; private final KnowledgeChunkService chunks;
    private final KnowledgeIndexTaskService tasks; private final KnowledgeAccessService access;
    private final FileUploadRepository files; private final GraphContributionService graph;
    private final KnowledgeSearchIndexService searchIndex;
    private final com.yizhaoqi.smartpai.config.KnowledgeRagProperties properties;
    public KnowledgeIndexOrchestrator(List<KnowledgeIndexBuilder> builders,GraphStore store,JdbcTemplate jdbc,KnowledgeChunkService chunks,
            KnowledgeIndexTaskService tasks,KnowledgeAccessService access,FileUploadRepository files,GraphContributionService graph,
            KnowledgeSearchIndexService searchIndex,com.yizhaoqi.smartpai.config.KnowledgeRagProperties properties) {
        for(var b:builders) this.builders.put(b.type(),b);
        this.store=store;this.jdbc=jdbc;this.chunks=chunks;this.tasks=tasks;this.access=access;this.files=files;this.graph=graph;
        this.searchIndex=searchIndex;this.properties=properties;
    }
    public List<String> acceptSources(long documentId,List<Chunk> sources,String requester) {
        return acceptSources(documentId,sources,requester,false);
    }
    public List<String> acceptSources(long documentId,List<Chunk> sources,String requester,boolean force) {
        var identity=access.resolve(requester); access.requireOwner(documentId,identity);
        return store.transaction(()->{
            FileUpload file=lockedFile(documentId,identity.userId());
            long set=chunks.seal(file,sources,access.scope(file));
            return schedule(file,set,Set.of(IndexType.CHUNK,IndexType.GRAPH),force);
        });
    }
    public List<String> rebuild(long documentId,Set<IndexType> types,String requester) {
        if(types==null || types.isEmpty()) throw new IllegalArgumentException("indexTypes 不能为空");
        var identity=access.resolve(requester); access.requireOwner(documentId,identity);
        return store.transaction(()->{
            FileUpload file=lockedFile(documentId,identity.userId());
            Long set=jdbc.queryForObject("SELECT current_chunk_set FROM knowledge_document WHERE document_id=? AND deleted=FALSE FOR UPDATE",Long.class,documentId);
            if(set==null) throw new IllegalStateException("请先为历史文档创建来源快照");
            return schedule(file,set,types,true);
        });
    }
    private FileUpload lockedFile(long id,String owner) {
        return jdbc.queryForObject("SELECT id,file_md5,file_name,user_id,org_tag,is_public FROM file_upload WHERE id=? FOR UPDATE",(rs,n)->{
            if(!owner.equals(rs.getString("user_id"))) throw new SecurityException("Document owner changed");
            var f=new FileUpload();f.setId(id);f.setFileMd5(rs.getString("file_md5"));f.setFileName(rs.getString("file_name"));
            f.setUserId(owner);f.setOrgTag(rs.getString("org_tag"));f.setPublic(rs.getBoolean("is_public"));return f;
        },id);
    }
    private List<String> schedule(FileUpload file,long set,Set<IndexType> types,boolean force) {
        List<String> jobs=new ArrayList<>();String scope=access.scope(file);
        for(IndexType type:IndexType.values()) if(types.contains(type)) {
            String config=builders.get(type).configVersion();
            var previous=jdbc.queryForList("SELECT * FROM knowledge_index_state WHERE document_id=? AND index_type=? FOR UPDATE",file.getId(),type.name());
            if(!force && !previous.isEmpty()) {
                var p=previous.get(0);
                if(((Number)p.get("target_chunk_set")).longValue()==set && config.equals(p.get("config_version")) && scope.equals(p.get("scope_id"))) { jobs.add((String)p.get("job_id"));continue; }
            }
            long revision=previous.isEmpty()?1:((Number)previous.get(0).get("build_revision")).longValue()+1;
            if(type==IndexType.CHUNK && revision>1) enqueueChunkCleanup(file.getId(),revision-1,file.getUserId(),"replace");
            if(type==IndexType.GRAPH) graph.suspend(file.getId(),file.getUserId());
            var context=new BuildContext(file.getId(),set,revision,config,scope,file.getUserId());
            String job=tasks.enqueue(type,context);
            jdbc.update("INSERT INTO knowledge_index_state(document_id,index_type,target_chunk_set,build_revision,config_version,scope_id,job_id) VALUES (?,?,?,?,?,?,?) "+
                "ON DUPLICATE KEY UPDATE target_chunk_set=VALUES(target_chunk_set),build_revision=VALUES(build_revision),config_version=VALUES(config_version),scope_id=VALUES(scope_id),job_id=VALUES(job_id),phase='PENDING',last_error=NULL,ready_chunk_set=NULL,ready_build_revision=NULL",
                file.getId(),type.name(),set,revision,config,scope,job);
            jdbc.update("INSERT INTO knowledge_artifact(id,index_type,scope_id,config_version) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE config_version=VALUES(config_version)",hash(type.name(),scope),type.name(),scope,config);
            jobs.add(job);
        }
        return List.copyOf(jobs);
    }
    public void execute(Claim claim) throws Exception {
        if(claim.type()==WorkType.DELETE_CHUNKS) {
            searchIndex.deleteChunksThrough(claim.configVersion(),Long.parseLong(claim.key().id()),claim.buildRevision());
            store.complete(claim,"SUCCEEDED");return;
        }
        BuildContext context=tasks.context(claim);IndexType type=claim.type()==WorkType.BUILD_CHUNK?IndexType.CHUNK:IndexType.GRAPH;
        var builder=builders.get(type);
        FileUpload file=files.findById(context.documentId()).orElse(null);
        if(file==null || !access.sameScope(file,context.scopeId()) || !current(type,context,false)) { store.complete(claim,"SUPERSEDED");return; }
        jdbc.update("UPDATE knowledge_index_state SET phase='BUILDING' WHERE document_id=? AND index_type=? AND build_revision=?",context.documentId(),type.name(),context.buildRevision());
        BuildResult result=builder.build(context,chunks.load(context.chunkSetId()),file);
        boolean accepted=tasks.fenced(claim,()->{
            FileUpload actual;
            try { actual=lockedFile(context.documentId(),context.requesterId()); }
            catch(org.springframework.dao.EmptyResultDataAccessException | SecurityException removed) { return false; }
            if(!current(type,context,true) || !builder.configVersion().equals(context.configVersion())) return false;
            if(!access.sameScope(actual,context.scopeId())) return false;
            if(result.graphBatch()!=null) graph.replace(result.graphBatch(),context.requesterId());
            String phase=type==IndexType.GRAPH?"SYNCING":"READY";
            jdbc.update("UPDATE knowledge_index_state SET phase=?,ready_chunk_set=?,ready_build_revision=?,physical_index=?,last_error=NULL WHERE document_id=? AND index_type=?",
                phase,context.chunkSetId(),context.buildRevision(),result.physicalIndex(),context.documentId(),type.name());
            if(type==IndexType.CHUNK) {
                jdbc.update("UPDATE knowledge_artifact SET service_status='AVAILABLE',physical_index=?,published_generation=published_generation+1 WHERE id=?",result.physicalIndex(),hash(type.name(),context.scopeId()));
                jdbc.update("UPDATE file_upload SET vectorization_status='COMPLETED',vectorization_error_message=NULL,actual_embedding_tokens=?,actual_chunk_count=? WHERE id=?",result.embeddingTokens(),result.itemCount(),context.documentId());
            }
            return true;
        });
        // Lease replacement can publish the same immutable generation. Only obsolete generations may be cleaned up.
        if(!accepted && type==IndexType.CHUNK && !current(type,context,false)) store.transaction(()->{
            enqueueChunkCleanup(context.documentId(),context.buildRevision(),context.requesterId(),claim.token());return null;
        });
    }
    private boolean current(IndexType type,BuildContext context,boolean lock) {
        var rows=jdbc.queryForList("SELECT s.build_revision,s.target_chunk_set,s.config_version,d.deleted,d.current_chunk_set FROM knowledge_index_state s JOIN knowledge_document d ON d.document_id=s.document_id WHERE s.document_id=? AND s.index_type=?"+(lock?" FOR UPDATE":""),context.documentId(),type.name());
        if(rows.isEmpty()) return false;var row=rows.get(0);
        return !Boolean.TRUE.equals(row.get("deleted")) && ((Number)row.get("current_chunk_set")).longValue()==context.chunkSetId()
            && ((Number)row.get("build_revision")).longValue()==context.buildRevision() && ((Number)row.get("target_chunk_set")).longValue()==context.chunkSetId() && context.configVersion().equals(row.get("config_version"));
    }
    /** Invoked inside the same transaction as the document deletion or ACL update. */
    public void withdraw(long documentId,String owner) {
        store.transaction(()->{
            lockedFile(documentId,owner); graph.withdraw(documentId,owner);
            var revisions=jdbc.queryForList("SELECT build_revision FROM knowledge_index_state WHERE document_id=? AND index_type='CHUNK' FOR UPDATE",Long.class,documentId);
            if(!revisions.isEmpty()) enqueueChunkCleanup(documentId,revisions.get(0),owner,"withdraw");
            jdbc.update("UPDATE knowledge_document SET deleted=TRUE,authorization_version=authorization_version+1 WHERE document_id=?",documentId);
            jdbc.update("UPDATE knowledge_index_state SET phase='REVOKED',ready_chunk_set=NULL,build_revision=build_revision+1 WHERE document_id=?",documentId);
            return null;
        });
    }
    private void enqueueChunkCleanup(long documentId,long revision,String owner,String reason) {
        store.enqueueTask(WorkType.DELETE_CHUNKS,new ObjectKey(Kind.ENTITY,Long.toString(documentId)),revision,revision,
            properties.getChunkIndexPrefix(),owner,"{}",reason);
    }
    /** ACL mutation and revocation of the old publication are committed atomically. */
    public List<String> updateAccess(long documentId,String orgTag,boolean isPublic,String requester) {
        var identity=access.resolve(requester);access.requireOwner(documentId,identity);
        if(orgTag!=null && !orgTag.isBlank() && !identity.orgTags().contains(orgTag)) throw new SecurityException("无权将文档移入该组织");
        return store.transaction(()->{
            var file=lockedFile(documentId,identity.userId());
            jdbc.update("UPDATE file_upload SET org_tag=?,is_public=? WHERE id=?",orgTag,isPublic,documentId);
            file.setOrgTag(orgTag);file.setPublic(isPublic);
            var sets=jdbc.queryForList("SELECT current_chunk_set FROM knowledge_document WHERE document_id=? AND deleted=FALSE FOR UPDATE",Long.class,documentId);
            if(sets.isEmpty() || sets.get(0)==null) { graph.suspend(documentId,identity.userId());return List.of(); }
            long set=chunks.seal(file,chunks.load(sets.get(0)).chunks(),access.scope(file));
            jdbc.update("UPDATE knowledge_document SET authorization_version=authorization_version+1 WHERE document_id=?",documentId);
            return schedule(file,set,Set.of(IndexType.CHUNK,IndexType.GRAPH),true);
        });
    }
}
