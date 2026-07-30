ALTER TABLE ai_task ADD COLUMN thread_id VARCHAR(64);
ALTER TABLE ai_task ADD COLUMN current_node VARCHAR(64);
ALTER TABLE ai_task ADD COLUMN progress INT NOT NULL DEFAULT 0;
ALTER TABLE ai_task ADD COLUMN attempt_no INT NOT NULL DEFAULT 0;
ALTER TABLE ai_task ADD COLUMN parent_task_id BIGINT;
ALTER TABLE ai_task ADD COLUMN idempotency_key VARCHAR(128);
ALTER TABLE ai_task ADD COLUMN error_code VARCHAR(64);
ALTER TABLE ai_task ADD COLUMN last_event_id VARCHAR(64);
ALTER TABLE ai_task ADD COLUMN input_tokens BIGINT;
ALTER TABLE ai_task ADD COLUMN output_tokens BIGINT;
ALTER TABLE ai_task ADD COLUMN model_name VARCHAR(100);
ALTER TABLE ai_task ADD COLUMN prompt_version VARCHAR(32);
ALTER TABLE ai_task ADD COLUMN duration_ms BIGINT;

ALTER TABLE ai_task
    ADD CONSTRAINT uk_ai_task_idempotency UNIQUE (idempotency_key);

CREATE INDEX idx_ai_task_thread_id ON ai_task (thread_id);

CREATE TABLE story_artifact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    artifact_type VARCHAR(32) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    content_json LONGTEXT NOT NULL,
    prompt_version VARCHAR(32),
    model_name VARCHAR(100),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_artifact_story FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT fk_story_artifact_task FOREIGN KEY (task_id) REFERENCES ai_task (id),
    CONSTRAINT uk_story_artifact_version UNIQUE (story_id, artifact_type, version_no)
);

CREATE INDEX idx_story_artifact_story_type
    ON story_artifact (story_id, artifact_type, version_no);

CREATE INDEX idx_story_artifact_task
    ON story_artifact (task_id);

-- V1 already contains error_message. Keep that column intact instead of adding
-- it again, so this migration works for both existing databases and clean installs.
