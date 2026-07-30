package com.storyforge.chapter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("local")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:story-memory-monotonic;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.chapter-workflow.redis-enabled=false"
})
@Transactional
class StoryMemoryMonotonicStateTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired StoryMemoryService service;

    @Test
    void terminalThreadAndForeshadowStatesCannotBeReopened() throws Exception {
        jdbc.update("INSERT INTO sys_user(username,password,vip_level,created_time) VALUES ('memory-user','#','FREE',CURRENT_TIMESTAMP)");
        long userId = jdbc.queryForObject("SELECT MAX(id) FROM sys_user", Long.class);
        jdbc.update("INSERT INTO story_project(user_id,title,genre,status,created_time,updated_time) VALUES (?,'记忆状态','悬疑','WORKFLOW_COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId);
        long storyId = jdbc.queryForObject("SELECT MAX(id) FROM story_project", Long.class);
        jdbc.update("INSERT INTO story_chapter(story_id,chapter_no,status,plan_status,word_count,row_version,created_time,updated_time) VALUES (?,1,'APPROVED','APPROVED',2,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", storyId);
        long chapterId = jdbc.queryForObject("SELECT MAX(id) FROM story_chapter", Long.class);
        jdbc.update("INSERT INTO story_chapter_version(chapter_id,version_no,source_type,content,content_hash,created_by,created_time) VALUES (?,1,'APPROVED','正文','e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',?,CURRENT_TIMESTAMP)", chapterId, userId);
        long versionId = jdbc.queryForObject("SELECT MAX(id) FROM story_chapter_version", Long.class);
        jdbc.update("UPDATE story_chapter SET current_version_id=? WHERE id=?", versionId, chapterId);

        StoryChapter chapter = new StoryChapter();
        chapter.setId(chapterId); chapter.setStoryId(storyId); chapter.setChapterNo(1);
        StoryChapterVersion version = new StoryChapterVersion(); version.setId(versionId);

        service.persistApproval(chapter, version, null, mapper.readTree("""
                {
                  "openedThreads":[
                    {"threadKey":"missing-ledger","description":"追查失踪账本","status":"OPEN"},
                    {"threadKey":"resolved-on-upsert","description":"本章当场解决","status":"OPEN"}
                  ],
                  "newForeshadowing":[{"foreshadowKey":"broken-watch","setup":"停在十一点的手表","status":"SETUP"}]
                }
                """));
        service.persistApproval(chapter, version, null, mapper.readTree("""
                {
                  "resolvedThreads":["missing-ledger"],
                  "paidOffForeshadowing":["broken-watch"]
                }
                """));
        service.persistApproval(chapter, version, null, mapper.readTree("""
                {
                  "updatedThreads":[
                    {"threadKey":"missing-ledger","description":"模型试图重新打开账本线","status":"OPEN"},
                    {"threadKey":"resolved-on-upsert","description":"本章当场解决","status":"RESOLVED"}
                  ],
                  "newForeshadowing":[{"foreshadowKey":"broken-watch","setup":"模型试图重新埋设手表","status":"SETUP"}]
                }
                """));

        assertThat(jdbc.queryForObject("SELECT status FROM story_plot_thread WHERE story_id=? AND thread_key='missing-ledger'", String.class, storyId))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT resolved_chapter_no FROM story_plot_thread WHERE story_id=? AND thread_key='missing-ledger'", Integer.class, storyId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM story_foreshadowing WHERE story_id=? AND foreshadow_key='broken-watch'", String.class, storyId))
                .isEqualTo("PAID_OFF");
        assertThat(jdbc.queryForObject("SELECT actual_payoff_chapter_no FROM story_foreshadowing WHERE story_id=? AND foreshadow_key='broken-watch'", Integer.class, storyId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM story_plot_thread WHERE story_id=? AND thread_key='resolved-on-upsert'", String.class, storyId))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT resolved_chapter_no FROM story_plot_thread WHERE story_id=? AND thread_key='resolved-on-upsert'", Integer.class, storyId))
                .isEqualTo(1);
    }
}
