CREATE TABLE story_final_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    report_version INT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'READY',
    report_json LONGTEXT NOT NULL,
    total_score INT NOT NULL,
    level VARCHAR(2) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32),
    model_name VARCHAR(100),
    task_id BIGINT,
    created_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_final_report_story FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT fk_final_report_user FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT fk_final_report_task FOREIGN KEY (task_id) REFERENCES ai_task (id),
    CONSTRAINT uk_final_report_version UNIQUE (story_id, report_version)
);

CREATE INDEX idx_final_report_story_created ON story_final_report (story_id, created_time);

CREATE TABLE story_release (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    release_no INT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT,
    tags_json LONGTEXT,
    outline_version_id BIGINT,
    report_id BIGINT,
    chapter_versions_json LONGTEXT NOT NULL,
    word_count INT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'LOCKED',
    created_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_release_story FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT fk_story_release_report FOREIGN KEY (report_id) REFERENCES story_final_report (id),
    CONSTRAINT fk_story_release_user FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT uk_story_release_version UNIQUE (story_id, release_no)
);

CREATE INDEX idx_story_release_story_created ON story_release (story_id, created_time);

CREATE TABLE export_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    format VARCHAR(16) NOT NULL,
    include_report BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    file_name VARCHAR(255),
    object_path VARCHAR(500),
    file_size BIGINT,
    content_type VARCHAR(120),
    download_token_hash VARCHAR(128),
    expires_time DATETIME(3),
    error_message VARCHAR(1000),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_export_task_story FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT fk_export_task_release FOREIGN KEY (release_id) REFERENCES story_release (id),
    CONSTRAINT fk_export_task_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_export_task_user_created ON export_task (user_id, created_time);

CREATE TABLE prompt_template (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    prompt_key VARCHAR(100) NOT NULL,
    prompt_type VARCHAR(50) NOT NULL,
    version_no INT NOT NULL,
    system_prompt LONGTEXT NOT NULL,
    user_template LONGTEXT NOT NULL,
    output_schema LONGTEXT,
    model_profile VARCHAR(64),
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    change_summary TEXT,
    created_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_time DATETIME(3),
    CONSTRAINT fk_prompt_template_user FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT uk_prompt_template_version UNIQUE (prompt_key, version_no)
);

CREATE INDEX idx_prompt_template_key_status ON prompt_template (prompt_key, status, version_no);

CREATE TABLE model_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    profile_key VARCHAR(64) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    temperature DECIMAL(4,3) NOT NULL DEFAULT 0.2,
    max_tokens INT NOT NULL DEFAULT 3000,
    secret_reference VARCHAR(255),
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_model_profile_user FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT uk_model_profile_key UNIQUE (profile_key)
);

CREATE TABLE ai_model_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT,
    story_id BIGINT,
    user_id BIGINT NOT NULL,
    agent_type VARCHAR(50) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    prompt_key VARCHAR(100),
    prompt_version INT,
    input_tokens BIGINT,
    output_tokens BIGINT,
    cached_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost DECIMAL(18,8),
    actual_cost DECIMAL(18,8),
    cost_status VARCHAR(24) NOT NULL DEFAULT 'ESTIMATED',
    duration_ms BIGINT,
    success BOOLEAN NOT NULL,
    error_code VARCHAR(64),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_model_usage_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_model_usage_story FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT fk_model_usage_task FOREIGN KEY (task_id) REFERENCES ai_task (id)
);

CREATE INDEX idx_model_usage_user_created ON ai_model_usage (user_id, created_time);

CREATE TABLE user_ai_wallet (
    user_id BIGINT PRIMARY KEY,
    available_credits BIGINT NOT NULL DEFAULT 0,
    frozen_credits BIGINT NOT NULL DEFAULT 0,
    consumed_credits BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_wallet_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE TABLE user_ai_credit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id BIGINT,
    operation_type VARCHAR(32) NOT NULL,
    amount BIGINT NOT NULL,
    balance_before BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    description VARCHAR(255),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_credit_log_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_credit_log_task FOREIGN KEY (task_id) REFERENCES ai_task (id),
    CONSTRAINT uk_credit_log_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_credit_log_user_created ON user_ai_credit_log (user_id, created_time);

CREATE TABLE user_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    story_id BIGINT,
    topic_score INT,
    character_score INT,
    outline_score INT,
    chapter_score INT,
    report_score INT,
    export_score INT,
    willingness VARCHAR(64),
    favorite_feature VARCHAR(100),
    biggest_problem TEXT,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_feedback_story FOREIGN KEY (story_id) REFERENCES story_project (id)
);

CREATE INDEX idx_feedback_story_created ON user_feedback (story_id, created_time);
