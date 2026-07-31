ALTER TABLE story_final_report ADD COLUMN word_count INT NOT NULL DEFAULT 0;

ALTER TABLE story_release ADD COLUMN characters_json LONGTEXT;
ALTER TABLE story_release ADD COLUMN outline_json LONGTEXT;

CREATE INDEX idx_model_usage_task_agent ON ai_model_usage (task_id, agent_type);
