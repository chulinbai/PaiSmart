package com.yizhaoqi.smartpai.service.knowledge.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;

/** Immutable commands and snapshots. Identity is scoped; a file MD5 is never an owner identity. */
public final class GraphTypes {
    private GraphTypes() {}
    public enum Kind {
        ENTITY("kg_entity", "kg_entity_source"), RELATION("kg_relation", "kg_relation_source");
        public final String table;
        public final String sourceTable;
        Kind(String table, String sourceTable) { this.table = table; this.sourceTable = sourceTable; }
    }
    public enum Direction { DIRECTED, SYMMETRIC, UNKNOWN }
    public enum WorkType { MERGE, SYNC, DELETE, BUILD_CHUNK, BUILD_GRAPH, DELETE_CHUNKS }
    public record Fact(Kind kind, String name, String source, String target, String predicate,
                       Direction direction, String description) {
        public Fact {
            if (kind == null || description == null || description.isBlank())
                throw new IllegalArgumentException("Fact kind and description are required");
            if (kind == Kind.ENTITY) required(name);
            else { required(source); required(target); required(predicate);
                if (direction == null) throw new IllegalArgumentException("Relationship direction is required"); }
        }
        public static Fact entity(String name, String description) {
            return new Fact(Kind.ENTITY, name, null, null, null, null, description);
        }
        public String identity() {
            if (kind == Kind.ENTITY) return hash("entity", normalized(name));
            String a = normalized(source), b = normalized(target);
            if (direction != Direction.DIRECTED && a.compareTo(b) > 0) { String tmp = a; a = b; b = tmp; }
            return hash("relation", a, b, normalized(predicate), direction.name());
        }
    }
    public record ChunkExtraction(String chunkKey, String sourceText, String extractionConfig, List<Fact> facts) {
        public ChunkExtraction {
            required(chunkKey); required(sourceText); required(extractionConfig);
            facts = List.copyOf(facts);
        }
    }
    public record DocumentBatch(long documentId, long sourceRevision, List<ChunkExtraction> chunks) {
        public DocumentBatch {
            if (documentId <= 0 || sourceRevision < 1) throw new IllegalArgumentException("Invalid document revision");
            chunks = List.copyOf(chunks);
            if (chunks.stream().map(ChunkExtraction::chunkKey).distinct().count() != chunks.size())
                throw new IllegalArgumentException("Duplicate chunk identity");
        }
    }
    public record ObjectKey(Kind kind, String id) implements Comparable<ObjectKey> {
        public ObjectKey { if (kind == null) throw new IllegalArgumentException("Kind required"); required(id); }
        public int compareTo(ObjectKey other) {
            int cmp = kind.compareTo(other.kind); return cmp == 0 ? id.compareTo(other.id) : cmp;
        }
    }
    public record Head(ObjectKey key, String scopeId, String label, long sourceVersion, long buildRevision,
                       long descriptionSourceVersion, String projectionId, String description,
                       String desiredConfig, boolean deleted, boolean queryable) {}
    public record Claim(String jobId, WorkType type, ObjectKey key, long sourceVersion, long buildRevision,
                        String configVersion, String requesterId, String token, String payload) {}
    public record Snapshot(Head head, List<String> contributions) {
        public Snapshot { contributions = List.copyOf(contributions); }
    }
    public record Projection(ObjectKey key, String scopeId, String label, long sourceVersion, long buildRevision,
                             String projectionId, String description, String configVersion) {}
    public record Change(boolean applied, List<ObjectKey> affected) {
        public Change { affected = List.copyOf(affected); }
    }
    public static String normalized(String value) {
        required(value);
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().toLowerCase(java.util.Locale.ROOT);
    }
    public static void required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Blank graph value");
    }
    public static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

