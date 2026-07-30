CREATE TABLE story_chapter (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    chapter_no INT NOT NULL,
    title VARCHAR(255),
    current_version_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    plan_status VARCHAR(32) NOT NULL DEFAULT 'NOT_PLANNED',
    plan_json LONGTEXT,
    word_count INT NOT NULL DEFAULT 0,
    row_version BIGINT NOT NULL DEFAULT 0,
    approved_time DATETIME(3),
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_chapter_story
        FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT uk_story_chapter UNIQUE (story_id, chapter_no)
);

CREATE INDEX idx_story_chapter_story_status
    ON story_chapter (story_id, status, chapter_no);

ALTER TABLE ai_task ADD COLUMN chapter_id BIGINT;
ALTER TABLE ai_task
    ADD CONSTRAINT fk_ai_task_chapter
        FOREIGN KEY (chapter_id) REFERENCES story_chapter (id);
CREATE INDEX idx_ai_task_chapter_created
    ON ai_task (chapter_id, created_time);

CREATE TABLE story_chapter_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    base_version_id BIGINT,
    ai_task_id BIGINT,
    idempotency_key VARCHAR(160),
    prompt_version VARCHAR(32),
    model_name VARCHAR(100),
    review_json LONGTEXT,
    change_summary TEXT,
    created_by BIGINT,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_chapter_version_chapter
        FOREIGN KEY (chapter_id) REFERENCES story_chapter (id),
    CONSTRAINT fk_chapter_version_base
        FOREIGN KEY (base_version_id) REFERENCES story_chapter_version (id),
    CONSTRAINT fk_chapter_version_task
        FOREIGN KEY (ai_task_id) REFERENCES ai_task (id),
    CONSTRAINT fk_chapter_version_user
        FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT uk_chapter_version UNIQUE (chapter_id, version_no),
    CONSTRAINT uk_chapter_version_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_chapter_version_chapter_created
    ON story_chapter_version (chapter_id, version_no);

ALTER TABLE story_chapter
    ADD CONSTRAINT fk_story_chapter_current_version
        FOREIGN KEY (current_version_id) REFERENCES story_chapter_version (id);

CREATE TABLE story_fact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    fact_key VARCHAR(128) NOT NULL,
    fact_type VARCHAR(32) NOT NULL,
    subject_name VARCHAR(100),
    predicate_name VARCHAR(100),
    fact_value TEXT NOT NULL,
    visibility VARCHAR(32),
    locked BOOLEAN NOT NULL DEFAULT FALSE,
    source_chapter_no INT,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_fact_story
        FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT uk_story_fact UNIQUE (story_id, fact_key)
);

CREATE TABLE story_relationship (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    relationship_key VARCHAR(220) NOT NULL,
    character_a VARCHAR(100) NOT NULL,
    character_b VARCHAR(100) NOT NULL,
    relation_name VARCHAR(100),
    trust_score INT,
    conflict_score INT,
    public_status VARCHAR(255),
    private_status VARCHAR(255),
    updated_at_chapter_no INT,
    state_json LONGTEXT,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_relationship_story
        FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT uk_story_relationship UNIQUE (story_id, relationship_key)
);

CREATE TABLE story_plot_thread (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    thread_key VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    introduced_chapter_no INT,
    expected_payoff_chapter_no INT,
    resolved_chapter_no INT,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    clues_json LONGTEXT,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_plot_thread_story
        FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT uk_story_plot_thread UNIQUE (story_id, thread_key)
);

CREATE TABLE story_foreshadowing (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    story_id BIGINT NOT NULL,
    foreshadow_key VARCHAR(128) NOT NULL,
    setup_text TEXT NOT NULL,
    setup_chapter_no INT,
    payoff_plan TEXT,
    payoff_chapter_no INT,
    actual_payoff_chapter_no INT,
    status VARCHAR(24) NOT NULL DEFAULT 'SETUP',
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_story_foreshadowing_story
        FOREIGN KEY (story_id) REFERENCES story_project (id),
    CONSTRAINT uk_story_foreshadowing UNIQUE (story_id, foreshadow_key)
);

CREATE TABLE story_chapter_summary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id BIGINT NOT NULL,
    chapter_version_id BIGINT NOT NULL,
    summary TEXT NOT NULL,
    main_events_json LONGTEXT,
    character_changes_json LONGTEXT,
    opened_threads_json LONGTEXT,
    resolved_threads_json LONGTEXT,
    ending_hook TEXT,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_chapter_summary_chapter
        FOREIGN KEY (chapter_id) REFERENCES story_chapter (id),
    CONSTRAINT fk_chapter_summary_version
        FOREIGN KEY (chapter_version_id) REFERENCES story_chapter_version (id),
    CONSTRAINT uk_chapter_summary_version UNIQUE (chapter_version_id)
);

CREATE INDEX idx_chapter_summary_chapter
    ON story_chapter_summary (chapter_id, created_time);

CREATE TABLE story_rewrite_proposal (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id BIGINT NOT NULL,
    base_version_id BIGINT NOT NULL,
    ai_task_id BIGINT,
    idempotency_key VARCHAR(160) NOT NULL,
    generation_no INT NOT NULL DEFAULT 1,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    selected_text TEXT NOT NULL,
    selected_text_hash VARCHAR(64) NOT NULL,
    action_type VARCHAR(40) NOT NULL,
    custom_instruction VARCHAR(1000),
    replacement_text TEXT,
    replacement_hash VARCHAR(64),
    reason VARCHAR(1000),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    resolved_version_id BIGINT,
    created_by BIGINT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_time DATETIME(3),
    CONSTRAINT fk_rewrite_proposal_chapter
        FOREIGN KEY (chapter_id) REFERENCES story_chapter (id),
    CONSTRAINT fk_rewrite_proposal_base
        FOREIGN KEY (base_version_id) REFERENCES story_chapter_version (id),
    CONSTRAINT fk_rewrite_proposal_task
        FOREIGN KEY (ai_task_id) REFERENCES ai_task (id),
    CONSTRAINT fk_rewrite_proposal_resolved_version
        FOREIGN KEY (resolved_version_id) REFERENCES story_chapter_version (id),
    CONSTRAINT fk_rewrite_proposal_user
        FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT uk_rewrite_proposal_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX idx_rewrite_proposal_chapter_status
    ON story_rewrite_proposal (chapter_id, status, created_time);

CREATE TABLE ai_task_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    redis_event_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    sequence_no BIGINT NOT NULL,
    status VARCHAR(30),
    current_node VARCHAR(64),
    progress INT,
    data_json LONGTEXT NOT NULL,
    created_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ai_task_event_task
        FOREIGN KEY (task_id) REFERENCES ai_task (id),
    CONSTRAINT uk_ai_task_event_redis UNIQUE (redis_event_id),
    CONSTRAINT uk_ai_task_event_sequence UNIQUE (task_id, sequence_no)
);

CREATE INDEX idx_ai_task_event_task_cursor
    ON ai_task_event (task_id, id);
