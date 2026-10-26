-- Additive MySQL 8 schema. kg_work / kg_outbox are shared by builds, projections and ES sync.
CREATE TABLE IF NOT EXISTS knowledge_chunk_set (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, document_id BIGINT NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, parser_config VARCHAR(64) NOT NULL,
 sealed BOOLEAN NOT NULL DEFAULT FALSE, expected_count INT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_chunk_snapshot(document_id,fingerprint,parser_config), INDEX idx_chunk_document(document_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS knowledge_chunk (
 chunk_set_id BIGINT NOT NULL, chunk_key VARCHAR(255) NOT NULL, ordinal INT NOT NULL,
 source_json LONGTEXT NOT NULL, PRIMARY KEY(chunk_set_id,chunk_key),
 CONSTRAINT fk_chunk_snapshot FOREIGN KEY(chunk_set_id) REFERENCES knowledge_chunk_set(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS knowledge_document (
 document_id BIGINT PRIMARY KEY, current_chunk_set BIGINT,
 authorization_version BIGINT NOT NULL DEFAULT 1, scope_id VARCHAR(64) NOT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_scope (
 id VARCHAR(64) PRIMARY KEY, policy_version INT NOT NULL DEFAULT 1,
 owner_id VARCHAR(64), org_tag VARCHAR(255), is_public BOOLEAN NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS knowledge_artifact (
 id VARCHAR(64) PRIMARY KEY, index_type VARCHAR(16) NOT NULL, scope_id VARCHAR(64) NOT NULL,
 config_version VARCHAR(64) NOT NULL, service_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY',
 physical_index VARCHAR(255), published_generation BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_artifact_scope(index_type,scope_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS knowledge_index_state (
 document_id BIGINT NOT NULL, index_type VARCHAR(16) NOT NULL,
 target_chunk_set BIGINT NOT NULL, ready_chunk_set BIGINT,
 build_revision BIGINT NOT NULL DEFAULT 1, ready_build_revision BIGINT,
 config_version VARCHAR(64) NOT NULL, scope_id VARCHAR(64) NOT NULL,
 phase VARCHAR(16) NOT NULL DEFAULT 'PENDING', job_id VARCHAR(36),
 physical_index VARCHAR(255), last_error VARCHAR(2000),
 PRIMARY KEY(document_id,index_type), INDEX idx_index_scope(scope_id,index_type,phase)
) ENGINE=InnoDB;
