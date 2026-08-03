ALTER TABLE story_project
    ADD COLUMN content_mode VARCHAR(20) NOT NULL DEFAULT 'SHORT_STORY';

ALTER TABLE story_project
    ADD COLUMN target_chapter_count INT;

ALTER TABLE story_project
    ADD COLUMN target_total_words INT;

ALTER TABLE story_project
    ADD COLUMN chapter_target_words INT;

ALTER TABLE story_project
    ADD COLUMN viewpoint VARCHAR(32);

ALTER TABLE story_project
    ADD COLUMN style_profile LONGTEXT;

CREATE INDEX idx_story_project_user_mode_created
    ON story_project (user_id, content_mode, created_time);
