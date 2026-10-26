-- MySQL 8. Additive schema for the graph update protocol. No changes to existing application tables.
CREATE TABLE IF NOT EXISTS kg_document_state (
 document_id BIGINT PRIMARY KEY, source_revision BIGINT NOT NULL, batch_hash VARCHAR(64) NOT NULL,
 scope_id VARCHAR(64) NOT NULL, payload_json LONGTEXT NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_chunk_extraction (
 id VARCHAR(64) PRIMARY KEY, document_id BIGINT NOT NULL, source_revision BIGINT NOT NULL,
 chunk_key VARCHAR(255) NOT NULL, input_hash VARCHAR(64) NOT NULL, result_json LONGTEXT NOT NULL,
 INDEX idx_kg_extraction_document(document_id, source_revision)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_entity (
 id VARCHAR(64) PRIMARY KEY, scope_id VARCHAR(64) NOT NULL, identity_key VARCHAR(64) NOT NULL,
 label VARCHAR(512) NOT NULL, source_entity_id VARCHAR(64), target_entity_id VARCHAR(64),
 predicate_label VARCHAR(255), direction_type VARCHAR(16),
 source_version BIGINT NOT NULL DEFAULT 0, build_revision BIGINT NOT NULL DEFAULT 0, description_source_version BIGINT NOT NULL DEFAULT 0,
 projection_id VARCHAR(36), description LONGTEXT, desired_config VARCHAR(64) NOT NULL,
 indexed_projection_id VARCHAR(36), deleted BOOLEAN NOT NULL DEFAULT FALSE, queryable BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_kg_entity_identity(scope_id, identity_key),
 INDEX idx_kg_entity_source(scope_id, source_entity_id),
 INDEX idx_kg_entity_target(scope_id, target_entity_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_entity_source (
 id VARCHAR(64) PRIMARY KEY, object_id VARCHAR(64) NOT NULL, document_id BIGINT NOT NULL,
 extraction_id VARCHAR(64) NOT NULL, fact_json LONGTEXT NOT NULL, description LONGTEXT NOT NULL,
 INDEX idx_kg_entity_document(document_id), INDEX idx_kg_entity_object(object_id),
 CONSTRAINT fk_kg_entity_object FOREIGN KEY (object_id) REFERENCES kg_entity(id),
 CONSTRAINT fk_kg_entity_extraction FOREIGN KEY (extraction_id) REFERENCES kg_chunk_extraction(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_relation (
 id VARCHAR(64) PRIMARY KEY, scope_id VARCHAR(64) NOT NULL, identity_key VARCHAR(64) NOT NULL,
 label VARCHAR(512) NOT NULL, source_entity_id VARCHAR(64), target_entity_id VARCHAR(64),
 predicate_label VARCHAR(255), direction_type VARCHAR(16),
 source_version BIGINT NOT NULL DEFAULT 0, build_revision BIGINT NOT NULL DEFAULT 0, description_source_version BIGINT NOT NULL DEFAULT 0,
 projection_id VARCHAR(36), description LONGTEXT, desired_config VARCHAR(64) NOT NULL,
 indexed_projection_id VARCHAR(36), deleted BOOLEAN NOT NULL DEFAULT FALSE, queryable BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_kg_relation_identity(scope_id, identity_key),
 INDEX idx_kg_relation_source(scope_id, source_entity_id),
 INDEX idx_kg_relation_target(scope_id, target_entity_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_relation_source (
 id VARCHAR(64) PRIMARY KEY, object_id VARCHAR(64) NOT NULL, document_id BIGINT NOT NULL,
 extraction_id VARCHAR(64) NOT NULL, fact_json LONGTEXT NOT NULL, description LONGTEXT NOT NULL,
 INDEX idx_kg_relation_document(document_id), INDEX idx_kg_relation_object(object_id),
 CONSTRAINT fk_kg_relation_object FOREIGN KEY (object_id) REFERENCES kg_relation(id),
 CONSTRAINT fk_kg_relation_extraction FOREIGN KEY (extraction_id) REFERENCES kg_chunk_extraction(id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_work (
 id VARCHAR(36) PRIMARY KEY, dedup_key VARCHAR(64) NOT NULL UNIQUE,
 work_type VARCHAR(16) NOT NULL, object_kind VARCHAR(16) NOT NULL, object_id VARCHAR(64) NOT NULL,
 source_version BIGINT NOT NULL, build_revision BIGINT NOT NULL, config_version VARCHAR(64) NOT NULL, requester_id VARCHAR(64) NOT NULL,
 payload_json LONGTEXT NOT NULL, work_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0, lease_token VARCHAR(36), lease_until TIMESTAMP(6) NULL,
 last_error VARCHAR(2000), last_dispatch_at TIMESTAMP(6) NULL, INDEX idx_kg_work_lease(work_status, lease_until)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS kg_outbox (
 id VARCHAR(36) PRIMARY KEY, job_id VARCHAR(36) NOT NULL, published BOOLEAN NOT NULL DEFAULT FALSE,
 attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 last_error VARCHAR(2000), INDEX idx_kg_outbox_pending(published, next_attempt_at),
 CONSTRAINT fk_kg_outbox_job FOREIGN KEY (job_id) REFERENCES kg_work(id)
) ENGINE=InnoDB;

