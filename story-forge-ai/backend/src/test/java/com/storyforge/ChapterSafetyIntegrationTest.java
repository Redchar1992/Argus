package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.storyforge.chapter.dto.AcceptProposalRequest;
import com.storyforge.chapter.dto.FinalizeChapterRequest;
import com.storyforge.chapter.dto.SaveChapterContentRequest;
import com.storyforge.chapter.service.ChapterApplicationService;
import com.storyforge.chapter.service.ChapterPersistenceService;
import com.storyforge.chapter.stream.ChapterCommandPublisher;
import com.storyforge.common.exception.ApiException;
import com.storyforge.task.producer.WorkflowDispatchException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:chapter-safety;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.chapter-workflow.redis-enabled=false"
})
class ChapterSafetyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ChapterPersistenceService persistence;
    @Autowired ChapterApplicationService application;
    @MockBean ChapterCommandPublisher publisher;

    @BeforeEach
    void clean() {
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
    }

    @Test
    void allVersionWritesRejectContentOverOneHundredThousandCharacters() throws Exception {
        Seed seed=seedChapter("REVIEW_REQUIRED","原始正文");
        ApiException saveFailure=org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                ()->persistence.saveContent(
                        seed.userId(),
                        seed.chapterId(),
                        new SaveChapterContentRequest(seed.versionId(),"甲".repeat(100_001),hash("原始正文"))
                )
        );
        assertThat(saveFailure.getCode()).isEqualTo("CHAPTER_CONTENT_TOO_LONG");
        assertThat(versionCount(seed.chapterId())).isEqualTo(1);

        String baseContent="甲".repeat(100_000);
        jdbc.update(
                "UPDATE story_chapter_version SET content=?,content_hash=? WHERE id=?",
                baseContent,
                hash(baseContent),
                seed.versionId()
        );
        jdbc.update("""
                INSERT INTO story_rewrite_proposal(
                    chapter_id,base_version_id,idempotency_key,generation_no,start_offset,end_offset,
                    selected_text,selected_text_hash,action_type,replacement_text,replacement_hash,
                    reason,status,created_by,created_time
                )
                VALUES (?,?,?,1,0,1,?,?,?, ?,?,'扩写','READY',?,CURRENT_TIMESTAMP)
                """,
                seed.chapterId(),
                seed.versionId(),
                "overlong-proposal",
                "甲",
                hash("甲"),
                "ENHANCE_CONFLICT",
                "甲乙丙",
                hash("甲乙丙"),
                seed.userId()
        );
        long proposalId=jdbc.queryForObject(
                "SELECT id FROM story_rewrite_proposal WHERE idempotency_key='overlong-proposal'",
                Long.class
        );

        ApiException acceptFailure=org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                ()->persistence.acceptProposal(
                        seed.userId(),
                        seed.chapterId(),
                        proposalId,
                        new AcceptProposalRequest(seed.versionId(),hash(baseContent))
                )
        );
        assertThat(acceptFailure.getCode()).isEqualTo("CHAPTER_CONTENT_TOO_LONG");
        assertThat(versionCount(seed.chapterId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM story_rewrite_proposal WHERE id=?",
                String.class,
                proposalId
        )).isEqualTo("READY");
    }

    @Test
    void finalizeRequiresReviewedSourceAndNoActiveChapterGeneration() throws Exception {
        Seed seed=seedChapter("RUNNING","等待审核的正文");

        ApiException sourceNotReady=org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                ()->persistence.prepareFinalize(seed.userId(),seed.chapterId(),true,"")
        );
        assertThat(sourceNotReady.getCode()).isEqualTo("CHAPTER_SOURCE_TASK_NOT_READY");

        jdbc.update("UPDATE ai_task SET status='REVIEW_REQUIRED' WHERE id=?",seed.sourceTaskId());
        jdbc.update("""
                INSERT INTO ai_task(
                    user_id,story_id,chapter_id,task_type,status,request_payload,thread_id,
                    idempotency_key,created_time,updated_time
                )
                VALUES (?,?,?,'CHAPTER_GENERATE','WAITING','{}','other-thread',
                        'active-generation',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,seed.userId(),seed.storyId(),seed.chapterId());
        ApiException activeTask=org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                ()->persistence.prepareFinalize(seed.userId(),seed.chapterId(),true,"")
        );
        assertThat(activeTask.getCode()).isEqualTo("CHAPTER_TASK_ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE chapter_id=? AND task_type='CHAPTER_FINALIZE'",
                Integer.class,
                seed.chapterId()
        )).isZero();
    }

    @Test
    void finalizeDispatchFailureRestoresReviewRequiredState() throws Exception {
        Seed seed=seedChapter("REVIEW_REQUIRED","可以批准的正文");
        when(publisher.publish(anyMap())).thenThrow(new WorkflowDispatchException("redis offline"));

        ApiException failure=org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class,
                ()->application.finalizeChapter(
                        seed.userId(),
                        seed.chapterId(),
                        new FinalizeChapterRequest(true,"")
                )
        );

        assertThat(failure.getCode()).isEqualTo("CHAPTER_QUEUE_UNAVAILABLE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM story_chapter WHERE id=?",
                String.class,
                seed.chapterId()
        )).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ai_task WHERE chapter_id=? AND task_type='CHAPTER_FINALIZE'",
                String.class,
                seed.chapterId()
        )).isEqualTo("FAILED");
    }

    private Seed seedChapter(String sourceStatus,String content)throws Exception{
        jdbc.update("""
                INSERT INTO sys_user(username,password,vip_level,created_time)
                VALUES ('safety-user','#','FREE',CURRENT_TIMESTAMP)
                """);
        long userId=jdbc.queryForObject("SELECT MAX(id) FROM sys_user",Long.class);
        jdbc.update("""
                INSERT INTO story_project(user_id,title,genre,status,created_time,updated_time)
                VALUES (?,'安全测试','都市','WORKFLOW_COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,userId);
        long storyId=jdbc.queryForObject("SELECT MAX(id) FROM story_project",Long.class);
        jdbc.update("""
                INSERT INTO story_chapter(
                    story_id,chapter_no,status,plan_status,plan_json,word_count,row_version,
                    created_time,updated_time
                )
                VALUES (?,1,'REVIEW_REQUIRED','APPROVED','{}',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,storyId);
        long chapterId=jdbc.queryForObject("SELECT MAX(id) FROM story_chapter",Long.class);
        jdbc.update("""
                INSERT INTO ai_task(
                    user_id,story_id,chapter_id,task_type,status,request_payload,thread_id,
                    idempotency_key,created_time,updated_time
                )
                VALUES (?,?,?,'CHAPTER_GENERATE',?,'{}','source-thread',
                        'source-generation',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,userId,storyId,chapterId,sourceStatus);
        long sourceTaskId=jdbc.queryForObject(
                "SELECT id FROM ai_task WHERE idempotency_key='source-generation'",
                Long.class
        );
        jdbc.update("""
                INSERT INTO story_chapter_version(
                    chapter_id,version_no,source_type,content,content_hash,ai_task_id,
                    idempotency_key,created_by,created_time
                )
                VALUES (?,1,'AI_DRAFT',?,?,?,'source-version',?,CURRENT_TIMESTAMP)
                """,chapterId,content,hash(content),sourceTaskId,userId);
        long versionId=jdbc.queryForObject(
                "SELECT id FROM story_chapter_version WHERE idempotency_key='source-version'",
                Long.class
        );
        jdbc.update("""
                UPDATE story_chapter
                SET current_version_id=?,row_version=1,word_count=?,status='REVIEW_REQUIRED'
                WHERE id=?
                """,versionId,content.length(),chapterId);
        return new Seed(userId,storyId,chapterId,versionId,sourceTaskId);
    }

    private int versionCount(long chapterId){
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM story_chapter_version WHERE chapter_id=?",
                Integer.class,
                chapterId
        );
    }

    private String hash(String value)throws Exception{
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private record Seed(long userId,long storyId,long chapterId,long versionId,long sourceTaskId){ }
}
