package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.chapter.service.ChapterTaskService;
import com.storyforge.chapter.stream.ChapterCommandPublisher;
import com.storyforge.task.AiTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:chapter-task-retry;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.chapter-workflow.redis-enabled=false"
})
class ChapterTaskRetryTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ChapterTaskService service;
    @MockBean ChapterCommandPublisher publisher;
    long userId;
    long storyId;
    long chapterId;

    @BeforeEach
    void seed() {
        reset(publisher);
        when(publisher.publish(anyMap())).thenReturn("command-1");
        jdbc.update("DELETE FROM ai_task_event");
        jdbc.update("DELETE FROM story_chapter_summary");
        jdbc.update("DELETE FROM story_rewrite_proposal");
        jdbc.update("UPDATE story_chapter SET current_version_id=NULL");
        jdbc.update("DELETE FROM story_chapter_version");
        jdbc.update("DELETE FROM story_artifact");
        jdbc.update("DELETE FROM ai_task");
        jdbc.update("DELETE FROM story_chapter");
        jdbc.update("DELETE FROM story_project");
        jdbc.update("DELETE FROM sys_user");
        jdbc.update("INSERT INTO sys_user(username,password,vip_level,created_time) VALUES ('retry-user','#','FREE',CURRENT_TIMESTAMP)");
        userId = jdbc.queryForObject("SELECT MAX(id) FROM sys_user", Long.class);
        jdbc.update("INSERT INTO story_project(user_id,title,genre,status,created_time,updated_time) VALUES (?,'重试','都市','WORKFLOW_COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", userId);
        storyId = jdbc.queryForObject("SELECT MAX(id) FROM story_project", Long.class);
        jdbc.update("INSERT INTO story_chapter(story_id,chapter_no,status,plan_status,word_count,row_version,created_time,updated_time) VALUES (?,1,'PLANNING','GENERATING',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", storyId);
        chapterId = jdbc.queryForObject("SELECT MAX(id) FROM story_chapter", Long.class);
    }

    @Test
    void failedRequestCreatesOneNewAttemptAndDuplicateReturnsIt() throws Exception {
        String logicalKey = "c" + chapterId + ":PLAN:1";
        var payload = mapper.createObjectNode().put("targetLength", 1600);
        AiTask failed = service.create(userId, storyId, chapterId, "CHAPTER_PLAN",
                "PLAN", logicalKey, payload, null);
        service.markFailed(failed, "temporary queue failure");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<AiTask> retries;
        try {
            retries = List.of(1, 2).stream().map(ignored -> CompletableFuture.supplyAsync(() -> {
                try {
                    barrier.await();
                    return service.create(userId, storyId, chapterId, "CHAPTER_PLAN",
                            "PLAN", logicalKey, payload, null);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }, executor)).toList().stream().map(CompletableFuture::join).toList();
        } finally {
            executor.shutdownNow();
        }

        AiTask retry = retries.get(0);
        assertThat(retries).extracting(AiTask::getId).containsOnly(retry.getId());
        assertThat(retry.getId()).isNotEqualTo(failed.getId());
        assertThat(retry.getIdempotencyKey()).isNotEqualTo(logicalKey).contains("chapter-retry:");
        assertThat(retry.getParentTaskId()).isEqualTo(failed.getId());
        assertThat(retry.getAttemptNo()).isEqualTo(1);
        assertThat(retry.getThreadId()).isEqualTo(failed.getThreadId());
        assertThat(service.create(userId, storyId, chapterId, "CHAPTER_PLAN",
                "PLAN", logicalKey, payload, null).getId()).isEqualTo(retry.getId());

        service.dispatch(retry, 1, "PLAN", payload);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE user_id=?", Integer.class, userId))
                .isEqualTo(2);
    }
}
