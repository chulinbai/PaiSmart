package com.yizhaoqi.smartpai.service.knowledge.graph;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.yizhaoqi.smartpai.service.knowledge.KnowledgeTypes.*;
import co.elastic.clients.json.JsonData;
import com.yizhaoqi.smartpai.client.MultiEmbeddingClient;
import com.yizhaoqi.smartpai.config.GraphProtocolProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.util.*;
import static com.yizhaoqi.smartpai.service.knowledge.graph.GraphTypes.*;

/** Immutable projection IDs prevent delayed writers from replacing a newer projection. */
public class KnowledgeSearchIndexService implements GraphSearchIndex {
    private final ElasticsearchClient client;
    private final MultiEmbeddingClient embeddings;
    private final ObjectMapper mapper;
    private final String prefix;
    public KnowledgeSearchIndexService(ElasticsearchClient client, MultiEmbeddingClient embeddings,
            ObjectMapper mapper, GraphProtocolProperties properties) {
        this.client=client; this.embeddings=embeddings; this.mapper=mapper; this.prefix=properties.getIndexPrefix();
        if (!prefix.matches("[a-z][a-z0-9_-]{0,80}")) throw new IllegalArgumentException("Invalid graph index prefix");
    }
    public void write(Projection projection, String requesterId) throws Exception {
        String model = embeddings.currentModelVersion();
        var result = embeddings.embedWithUsage(List.of(MultiEmbeddingClient.EmbeddingInput.text(projection.description())),
            requesterId, MultiEmbeddingClient.UsageType.UPLOAD);
        if (!model.equals(result.modelVersion()) || result.vectors().size() != 1)
            throw new IllegalStateException("Embedding model or vector count changed");
        float[] raw = result.vectors().get(0);
        if (raw.length == 0) throw new IllegalStateException("Empty embedding");
        List<Float> vector = new ArrayList<>(raw.length);
        for (float v : raw) { if (!Float.isFinite(v)) throw new IllegalStateException("Non-finite embedding"); vector.add(v); }
        String index = prefix + "_" + projection.key().kind().name().toLowerCase(Locale.ROOT) + "_" + hash(model).substring(0,16);
        ensureIndex(index, raw.length);
        Map<String,Object> document = new LinkedHashMap<>();
        document.put("objectId", projection.key().id()); document.put("kind", projection.key().kind().name());
        document.put("scopeId", projection.scopeId()); document.put("sourceVersion", projection.sourceVersion());
        document.put("buildRevision", projection.buildRevision()); document.put("projectionId", projection.projectionId());
        document.put("configVersion", projection.configVersion()); document.put("modelVersion", model);
        document.put("label", projection.label()); document.put("description", projection.description()); document.put("vector", vector);
        var response=client.index(i -> i.index(index).id(projection.projectionId()).document(document)
            .refresh(Refresh.WaitFor).timeout(t -> t.time("30s")));
        if(response.shards().failed().intValue()>0) throw new IllegalStateException("Graph projection shard write failed");
    }
    private void ensureIndex(String name, int dimensions) throws Exception {
        if (client.indices().exists(e -> e.index(name)).value()) return;
        Map<String,Object> fields = new LinkedHashMap<>();
        for (String field : List.of("objectId","kind","scopeId","projectionId","configVersion","modelVersion"))
            fields.put(field, Map.of("type", "keyword"));
        for (String field : List.of("sourceVersion","buildRevision")) fields.put(field, Map.of("type", "long"));
        fields.put("label", Map.of("type","text")); fields.put("description", Map.of("type","text"));
        fields.put("vector", Map.of("type","dense_vector","dims",dimensions,"index",true,"similarity","cosine"));
        String mapping = mapper.writeValueAsString(Map.of("mappings", Map.of("dynamic","strict","properties",fields)));
        try { client.indices().create(c -> c.index(name).withJson(new StringReader(mapping))); }
        catch (ElasticsearchException e) {
            if (!"resource_already_exists_exception".equals(e.error().type())) throw e;
        }
    }
    public void deleteThrough(ObjectKey key, long sourceVersion) throws Exception {
        var response=client.deleteByQuery(d -> d.index(prefix + "_*").allowNoIndices(true).ignoreUnavailable(true)
            .conflicts(Conflicts.Proceed).refresh(true).query(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field("objectId").value(key.id())))
                .filter(f -> f.term(t -> t.field("kind").value(key.kind().name())))
                .filter(f -> f.range(r -> r.field("sourceVersion").lte(JsonData.of(sourceVersion)))))));
        if(response.timedOut() || !response.failures().isEmpty()) throw new IllegalStateException("Graph deletion incomplete");
    }
    public void discard(Projection projection) throws Exception {
        client.deleteByQuery(d -> d.index(prefix + "_*").allowNoIndices(true).ignoreUnavailable(true)
            .conflicts(Conflicts.Proceed).refresh(true)
            .query(q -> q.term(t -> t.field("projectionId").value(projection.projectionId()))));
    }

    public String modelVersion() { return embeddings.currentModelVersion(); }
    public void deleteChunksThrough(String chunkPrefix,long documentId,long buildRevision) throws Exception {
        chunkIndexName(chunkPrefix, "validate-prefix");
        var response=client.deleteByQuery(d->d.index(chunkPrefix+"_*").allowNoIndices(true).ignoreUnavailable(true)
            .conflicts(Conflicts.Proceed).refresh(true).query(q->q.bool(b->b
                .filter(f->f.term(t->t.field("documentId").value(documentId)))
                .filter(f->f.range(r->r.field("buildRevision").lte(JsonData.of(buildRevision)))))));
        if(response.timedOut() || !response.failures().isEmpty() || response.versionConflicts()>0)
            throw new IllegalStateException("Chunk deletion incomplete; retry required");
    }
    public String chunkIndexName(String chunkPrefix,String model) {
        if(!chunkPrefix.matches("[a-z][a-z0-9_-]{0,80}")) throw new IllegalArgumentException("Invalid chunk index prefix");
        return chunkPrefix+"_"+hash(model).substring(0,16);
    }
    public void writeChunks(String index,BuildContext context,List<Chunk> chunks,List<float[]> vectors,String model) throws Exception {
        if(chunks.size()!=vectors.size() || chunks.isEmpty()) throw new IllegalStateException("Invalid embedding batch");
        int dimensions=vectors.get(0).length;
        if(!client.indices().exists(e->e.index(index)).value()) {
            Map<String,Object> fields=new LinkedHashMap<>();
            for(String field:List.of("scopeId","chunkKey","modelVersion","configVersion","kind")) fields.put(field,Map.of("type","keyword"));
            for(String field:List.of("documentId","chunkSetId","buildRevision")) fields.put(field,Map.of("type","long"));
            fields.put("description",Map.of("type","text")); fields.put("vector",Map.of("type","dense_vector","dims",dimensions,"index",true,"similarity","cosine"));
            String mapping=mapper.writeValueAsString(Map.of("mappings",Map.of("dynamic","strict","properties",fields)));
            try { client.indices().create(c->c.index(index).withJson(new StringReader(mapping))); }
            catch(ElasticsearchException e) { if(!"resource_already_exists_exception".equals(e.error().type())) throw e; }
        }
        var bulk=new co.elastic.clients.elasticsearch.core.BulkRequest.Builder().refresh(Refresh.WaitFor).timeout(t->t.time("30s"));
        for(int i=0;i<chunks.size();i++) {
            Chunk chunk=chunks.get(i); float[] raw=vectors.get(i);
            if(raw.length!=dimensions || raw.length==0) throw new IllegalStateException("Inconsistent vector dimensions");
            List<Float> vector=new ArrayList<>(); for(float v:raw) { if(!Float.isFinite(v)) throw new IllegalStateException("Non-finite embedding"); vector.add(v); }
            Map<String,Object> document=new LinkedHashMap<>();
            document.put("documentId",context.documentId());document.put("chunkSetId",context.chunkSetId());document.put("buildRevision",context.buildRevision());
            document.put("chunkKey",chunk.key());document.put("scopeId",context.scopeId());document.put("modelVersion",model);document.put("configVersion",context.configVersion());
            document.put("kind",chunk.type());document.put("description",chunk.text());document.put("vector",vector);
            String id=hash(Long.toString(context.documentId()),Long.toString(context.chunkSetId()),Long.toString(context.buildRevision()),chunk.key());
            bulk.operations(op->op.index(b->b.index(index).id(id).document(document)));
        }
        var response=client.bulk(bulk.build());
        if(response.errors() || response.items().stream().anyMatch(i->i.status()>=300 || i.error()!=null || (i.shards()!=null && i.shards().failed().intValue()>0)))
            throw new IllegalStateException("Chunk batch has failed items; immutable IDs allow safe replay");
    }
    public List<Map<String,Object>> candidates(String index,String query,String mode,Set<String> scopes,List<Long> documents,int limit,String requester) throws Exception {
        if(scopes.isEmpty()) return List.of();
        var scopeValues=scopes.stream().sorted().map(FieldValue::of).toList();
        Query filter=Query.of(q->q.bool(b->{
            b.filter(f->f.terms(t->t.field("scopeId").terms(v->v.value(scopeValues))));
            if(!documents.isEmpty()) b.filter(f->f.terms(t->t.field("documentId").terms(v->v.value(documents.stream().map(FieldValue::of).toList()))));
            return b;
        }));
        List<Float> vector=new ArrayList<>();
        if(!"keyword".equals(mode)) {
            String expected=embeddings.currentModelVersion();
            var result=embeddings.embedWithUsage(List.of(MultiEmbeddingClient.EmbeddingInput.text(query)),requester,MultiEmbeddingClient.UsageType.QUERY);
            if(!expected.equals(result.modelVersion()) || result.vectors().size()!=1) throw new IllegalStateException("Query embedding configuration changed");
            for(float v:result.vectors().get(0)) vector.add(v);
        }
        var response=client.search(s->{
            s.index(index).allowNoIndices(true).ignoreUnavailable(true).size(limit);
            if(!"vector".equals(mode)) s.query(q->q.bool(b->b.filter(filter).must(m->m.match(t->t.field("description").query(query)))));
            if(!vector.isEmpty()) s.knn(k->k.field("vector").queryVector(vector).k(limit).numCandidates(Math.min(10000,Math.max(100,limit*5))).filter(filter));
            return s;
        },Map.class);
        if(response.timedOut() || response.shards().failed().intValue()>0) throw new IllegalStateException("Incomplete ES search");
        List<Map<String,Object>> result=new ArrayList<>();
        for(var hit:response.hits().hits()) if(hit.source()!=null) {
            Map<String,Object> source=new LinkedHashMap<>(); hit.source().forEach((key,value)->source.put(String.valueOf(key),value)); result.add(source);
        }
        return List.copyOf(result);
    }
    public String graphIndex(Kind kind,boolean keyword) { return prefix+"_"+kind.name().toLowerCase(Locale.ROOT)+"_"+(keyword?"*":hash(modelVersion()).substring(0,16)); }
}

