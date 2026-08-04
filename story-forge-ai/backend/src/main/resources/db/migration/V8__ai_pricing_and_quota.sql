CREATE TABLE ai_operation_pricing (
    operation_type VARCHAR(64) PRIMARY KEY,
    credits BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT INTO ai_operation_pricing (operation_type, credits, enabled) VALUES
    ('TOPIC_GENERATION', 5, TRUE),
    ('WORKFLOW_CHARACTER', 8, TRUE),
    ('WORKFLOW_OUTLINE', 12, TRUE),
    ('WORKFLOW_SCORE', 4, TRUE),
    ('WORKFLOW_REVISION', 12, TRUE),
    ('CHAPTER_PLAN', 3, TRUE),
    ('CHAPTER_GENERATE', 12, TRUE),
    ('CHAPTER_REWRITE', 6, TRUE),
    ('CHAPTER_FINALIZE', 5, TRUE),
    ('FINAL_REVIEW', 30, TRUE);

CREATE TABLE user_ai_entitlement (
    user_id BIGINT PRIMARY KEY,
    plan_code VARCHAR(32) NOT NULL DEFAULT 'PILOT',
    daily_limit BIGINT NOT NULL DEFAULT 100,
    monthly_limit BIGINT NOT NULL DEFAULT 1000,
    max_concurrent_tasks INT NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_entitlement_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE TABLE user_ai_usage_period (
    user_id BIGINT NOT NULL,
    period_type VARCHAR(16) NOT NULL,
    period_start DATE NOT NULL,
    reserved_credits BIGINT NOT NULL DEFAULT 0,
    consumed_credits BIGINT NOT NULL DEFAULT 0,
    task_count BIGINT NOT NULL DEFAULT 0,
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, period_type, period_start),
    CONSTRAINT fk_ai_usage_period_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_ai_usage_period_user_time
    ON user_ai_usage_period (user_id, period_type, period_start);

CREATE TABLE ai_quota_reservation (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    daily_start DATE NOT NULL,
    monthly_start DATE NOT NULL,
    credits BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'FROZEN',
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_quota_reservation_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_ai_quota_reservation_user_status
    ON ai_quota_reservation (user_id, status);
