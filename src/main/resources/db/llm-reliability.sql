-- MySQL 8 incremental migration. Run once before deploying with ddl-auto=validate.
-- Development ddl-auto=update creates these fields automatically; do not run the ALTERs twice.
ALTER TABLE agent_runs
  MODIFY run_status VARCHAR(24) NOT NULL,
  ADD recovery_attempts INT NOT NULL DEFAULT 0,
  ADD cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
  ADD finalization_token VARCHAR(36) NULL,
  ADD finalization_intent LONGTEXT NULL,
  ADD finalization_attempts INT NOT NULL DEFAULT 0,
  ADD finalization_started_at DATETIME(6) NULL,
  ADD next_recovery_at DATETIME(6) NULL,
  ADD persistence_error VARCHAR(1000) NULL;

ALTER TABLE conversations
  ADD generation_id VARCHAR(64) NULL,
  ADD answer_status VARCHAR(24) NULL,
  ADD finish_reason VARCHAR(64) NULL,
  ADD response_metadata_json LONGTEXT NULL,
  ADD CONSTRAINT uk_conversation_generation UNIQUE (generation_id);

CREATE TABLE IF NOT EXISTS llm_request_attempt (
  id VARCHAR(36) PRIMARY KEY,
  generation_id VARCHAR(64), job_id VARCHAR(128), logical_call_id VARCHAR(64) NOT NULL,
  user_id VARCHAR(64), provider VARCHAR(64), model VARCHAR(255), provider_request_id VARCHAR(255), purpose VARCHAR(64),
  provider_snapshot_json TEXT, raw_usage_json TEXT, late_usage_json TEXT,
  phase VARCHAR(24) NOT NULL, reservation_status VARCHAR(24) NOT NULL, settlement_status VARCHAR(32) NOT NULL,
  estimated_prompt_tokens INT NOT NULL, requested_completion_tokens INT NOT NULL, observed_output_tokens INT NOT NULL,
  prompt_tokens INT, completion_tokens INT, late_prompt_tokens INT, late_completion_tokens INT, frozen_tokens BIGINT, usage_source VARCHAR(255),
  settlement_version INT NOT NULL, metric_key VARCHAR(255), reservation_fingerprint VARCHAR(64), settlement_fingerprint VARCHAR(64),
  reservation_json LONGTEXT, receipt_json LONGTEXT, extra_request BOOLEAN NOT NULL, sent BOOLEAN NOT NULL,
  diagnostic VARCHAR(1000), created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  INDEX idx_attempt_generation(generation_id), INDEX idx_attempt_settlement(settlement_status,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_tool_execution (
  id VARCHAR(64) PRIMARY KEY, generation_id VARCHAR(64) NOT NULL, logical_call_id VARCHAR(64) NOT NULL,
  tool_call_id VARCHAR(255) NOT NULL, tool_name VARCHAR(128) NOT NULL, arguments_fingerprint VARCHAR(64) NOT NULL,
  arguments_json LONGTEXT, status VARCHAR(24) NOT NULL, result_json LONGTEXT, references_json LONGTEXT,
  diagnostic VARCHAR(1000), created_at DATETIME(6), updated_at DATETIME(6),
  CONSTRAINT uk_tool_execution_identity UNIQUE(generation_id,logical_call_id,tool_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
