package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.storyforge.chapter.dto.SaveChapterContentRequest;
import com.storyforge.chapter.service.ChapterApplicationService;
import com.storyforge.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:chapter-concurrency;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class ChapterVersionConcurrencyTest {
    @Autowired JdbcTemplate jdbc;@Autowired ChapterApplicationService service;
    long userId,storyId,chapterId,versionId;
    @BeforeEach void seed(){
        jdbc.update("DELETE FROM ai_task_event");jdbc.update("DELETE FROM story_chapter_summary");
        jdbc.update("DELETE FROM story_rewrite_proposal");jdbc.update("UPDATE story_chapter SET current_version_id=NULL");
        jdbc.update("DELETE FROM story_chapter_version");jdbc.update("DELETE FROM story_artifact");jdbc.update("DELETE FROM ai_task");
        jdbc.update("DELETE FROM story_chapter");jdbc.update("DELETE FROM story_project");jdbc.update("DELETE FROM sys_user");
        jdbc.update("INSERT INTO sys_user(username,password,vip_level,created_time) VALUES ('parallel','#','FREE',CURRENT_TIMESTAMP)");
        userId=jdbc.queryForObject("SELECT MAX(id) FROM sys_user",Long.class);
        jdbc.update("INSERT INTO story_project(user_id,title,genre,status,created_time,updated_time) VALUES (?,'并发','都市','WORKFLOW_COMPLETED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",userId);
        storyId=jdbc.queryForObject("SELECT MAX(id) FROM story_project",Long.class);
        jdbc.update("INSERT INTO story_chapter(story_id,chapter_no,status,plan_status,word_count,row_version,created_time,updated_time) VALUES (?,1,'REVIEW_REQUIRED','APPROVED',2,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",storyId);
        chapterId=jdbc.queryForObject("SELECT MAX(id) FROM story_chapter",Long.class);
        jdbc.update("INSERT INTO story_chapter_version(chapter_id,version_no,source_type,content,content_hash,created_by,created_time) VALUES (?,1,'AI_DRAFT','初稿','e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',?,CURRENT_TIMESTAMP)",chapterId,userId);
        versionId=jdbc.queryForObject("SELECT MAX(id) FROM story_chapter_version",Long.class);
        jdbc.update("UPDATE story_chapter SET current_version_id=? WHERE id=?",versionId,chapterId);
    }
    @Test void optimisticBaseVersionAllowsExactlyOneConcurrentSave() throws Exception {
        CyclicBarrier barrier=new CyclicBarrier(2);ExecutorService executor=Executors.newFixedThreadPool(2);
        try{
            List<CompletableFuture<Object>> calls=List.of("版本甲","版本乙").stream().map(content->CompletableFuture.supplyAsync(()->{
                try{barrier.await();return service.saveContent(userId,chapterId,new SaveChapterContentRequest(versionId,content,null));}
                catch(Exception exception){return exception;}
            },executor)).toList();
            List<Object> outcomes=calls.stream().map(CompletableFuture::join).toList();
            assertThat(outcomes.stream().filter(v->!(v instanceof Exception)).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(ApiException.class::isInstance).map(ApiException.class::cast)
                    .map(ApiException::getCode).toList()).containsExactly("CHAPTER_VERSION_CONFLICT");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_chapter_version WHERE chapter_id=?",Integer.class,chapterId)).isEqualTo(2);
        }finally{executor.shutdownNow();}
    }
}
