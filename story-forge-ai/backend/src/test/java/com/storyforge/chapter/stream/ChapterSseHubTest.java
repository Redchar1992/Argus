package com.storyforge.chapter.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import com.storyforge.chapter.vo.TaskEventResponse;
import com.storyforge.common.config.ChapterWorkflowProperties;
import org.junit.jupiter.api.Test;

class ChapterSseHubTest {

    @Test
    void publishingArbitraryTaskIdsUsesBoundedLockStripes() {
        ChapterSseHub hub=newHub();

        for(long taskId=1;taskId<=10_000;taskId++){
            hub.publish(event(taskId,"TOKEN_DELTA","RUNNING"));
        }

        assertThat(hub.lockStripeCount()).isEqualTo(256);
    }

    @Test
    void terminalEventTypeOnlyClosesSubscriptionWithMatchingTerminalStatus() {
        ChapterSseHub hub=newHub();

        assertThat(hub.terminal(event(1L,"CHAPTER_PLAN_READY","RUNNING"))).isFalse();
        assertThat(hub.terminal(event(1L,"CHAPTER_PLAN_READY","SUCCESS"))).isTrue();
        assertThat(hub.terminal(event(1L,"HUMAN_REVIEW_REQUIRED","RUNNING"))).isFalse();
        assertThat(hub.terminal(event(1L,"HUMAN_REVIEW_REQUIRED","REVIEW_REQUIRED"))).isTrue();
        assertThat(hub.terminal(event(1L,"TASK_FAILED","RUNNING"))).isFalse();
        assertThat(hub.terminal(event(1L,"TASK_FAILED","FAILED"))).isTrue();
    }

    private ChapterSseHub newHub(){
        return new ChapterSseHub(new ChapterWorkflowProperties(
                false,
                "story:chapter:commands",
                "story:chapter:events",
                "backend",
                "consumer",
                Duration.ofSeconds(1),
                10,
                Duration.ofSeconds(60),
                10_000,
                20,
                100_000,
                10_000,
                1_800_000
        ));
    }

    private TaskEventResponse event(long taskId,String type,String status){
        return new TaskEventResponse(
                taskId+"-0",
                taskId,
                1L,
                1L,
                1,
                type,
                1L,
                status,
                "generate",
                50,
                null,
                null,
                null,
                LocalDateTime.now()
        );
    }
}
