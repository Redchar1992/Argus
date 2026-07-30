CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(100) NOT NULL,
    vip_level VARCHAR(30) NOT NULL DEFAULT 'FREE',
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE story_project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    genre VARCHAR(50) NOT NULL,
    audience VARCHAR(100),
    keywords VARCHAR(500),
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    selected_topic LONGTEXT,
    generated_topics LONGTEXT,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_project_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE INDEX idx_story_project_user_created
    ON story_project (user_id, created_time);

CREATE TABLE ai_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    story_id BIGINT NOT NULL,
    task_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    request_payload LONGTEXT NOT NULL,
    result_payload LONGTEXT,
    error_message VARCHAR(1000),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_task_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_ai_task_story FOREIGN KEY (story_id) REFERENCES story_project (id)
);

CREATE INDEX idx_ai_task_story_created
    ON ai_task (story_id, created_time);
