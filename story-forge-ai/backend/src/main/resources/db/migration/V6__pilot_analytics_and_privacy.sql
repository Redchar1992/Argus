ALTER TABLE sys_user ADD COLUMN privacy_version VARCHAR(32);
ALTER TABLE sys_user ADD COLUMN privacy_accepted_time DATETIME(3);

CREATE TABLE product_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_name VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    story_id BIGINT,
    task_id BIGINT,
    idempotency_key VARCHAR(160) NOT NULL,
    properties_json LONGTEXT,
    occurred_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_product_event_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_product_event_story FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT fk_product_event_task FOREIGN KEY (task_id) REFERENCES ai_task (id),
    CONSTRAINT uk_product_event_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_product_event_name_time
    ON product_event (event_name, occurred_time);

CREATE INDEX idx_product_event_user_time
    ON product_event (user_id, occurred_time);

CREATE INDEX idx_product_event_story_time
    ON product_event (story_id, occurred_time);
