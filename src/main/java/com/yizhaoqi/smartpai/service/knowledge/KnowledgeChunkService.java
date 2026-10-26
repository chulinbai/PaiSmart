package com.yizhaoqi.smartpai.service.knowledge;

import com.yizhaoqi.smartpai.config.KnowledgeRagProperties;
import com.yizhaoqi.smartpai.model.FileUpload;
import com.yizhaoqi.smartpai.service.knowledge.graph.GraphStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.hash;

@Service
@ConditionalOnProperty(name="knowledge.rag.enabled",havingValue="true")
public class KnowledgeChunkService {
    private final JdbcTemplate jdbc; private final GraphStore store; private final KnowledgeRagProperties properties;
    public KnowledgeChunkService(JdbcTemplate jdbc,GraphStore store,KnowledgeRagProperties properties) { this.jdbc=jdbc;this.store=store;this.properties=properties; }
    /** Caller seals and enqueues all builders in one transaction; workers can only read sealed sets. */
    public long seal(FileUpload file,List<Chunk> chunks,String scope) {
        String config=hash(properties.getParserVersion()), fingerprint=hash(store.json(chunks));
        if(chunks.isEmpty() || chunks.stream().map(Chunk::key).distinct().count()!=chunks.size()) throw new IllegalArgumentException("Empty or duplicate chunks");
        jdbc.queryForObject("SELECT id FROM file_upload WHERE id=? FOR UPDATE",Long.class,file.getId());
        jdbc.update("INSERT INTO knowledge_document(document_id,scope_id) VALUES (?,?) ON DUPLICATE KEY UPDATE document_id=document_id",file.getId(),scope);
        var doc=jdbc.queryForMap("SELECT * FROM knowledge_document WHERE document_id=? FOR UPDATE",file.getId());
        if(Boolean.TRUE.equals(doc.get("deleted"))) throw new IllegalStateException("Document deleted");
        jdbc.update("INSERT INTO knowledge_chunk_set(document_id,fingerprint,parser_config,expected_count) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            file.getId(),fingerprint,config,chunks.size());
        long id=jdbc.queryForObject("SELECT id FROM knowledge_chunk_set WHERE document_id=? AND fingerprint=? AND parser_config=?",Long.class,file.getId(),fingerprint,config);
        boolean sealed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT sealed FROM knowledge_chunk_set WHERE id=?",Boolean.class,id));
        if(!sealed) {
            for(int start=0;start<chunks.size();start+=100) {
                List<Object[]> rows=new ArrayList<>();
                for(int i=start;i<Math.min(chunks.size(),start+100);i++) rows.add(new Object[]{id,chunks.get(i).key(),i,store.json(chunks.get(i))});
                jdbc.batchUpdate("INSERT INTO knowledge_chunk(chunk_set_id,chunk_key,ordinal,source_json) VALUES (?,?,?,?)",rows);
            }
            jdbc.update("UPDATE knowledge_chunk_set SET sealed=TRUE WHERE id=?",id);
        }
        jdbc.update("UPDATE knowledge_document SET current_chunk_set=?,scope_id=? WHERE document_id=?",id,scope,file.getId());
        jdbc.update("INSERT INTO kg_scope(id,owner_id,org_tag,is_public) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
            scope,file.isPublic()?null:file.getUserId(),file.isPublic()?null:file.getOrgTag(),file.isPublic());
        return id;
    }
    public ChunkSet load(long id) {
        var row=jdbc.queryForMap("SELECT * FROM knowledge_chunk_set WHERE id=? AND sealed=TRUE",id);
        List<Chunk> chunks=jdbc.query("SELECT source_json FROM knowledge_chunk WHERE chunk_set_id=? ORDER BY ordinal",(rs,n)->store.fromJson(rs.getString(1),Chunk.class),id);
        if(chunks.size()!=((Number)row.get("expected_count")).intValue()) throw new IllegalStateException("Incomplete sealed snapshot");
        return new ChunkSet(id,((Number)row.get("document_id")).longValue(),(String)row.get("fingerprint"),(String)row.get("parser_config"),chunks);
    }
    public Chunk chunk(long set,String key) { return jdbc.queryForObject("SELECT source_json FROM knowledge_chunk WHERE chunk_set_id=? AND chunk_key=?",(rs,n)->store.fromJson(rs.getString(1),Chunk.class),set,key); }
}
