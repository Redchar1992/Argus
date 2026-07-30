package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;

import com.storyforge.task.AiTaskStatus;
import org.junit.jupiter.api.Test;

class AiTaskStatusTest {

    @Test
    void failedDeliveryCanRecoverEvenWhenIntermediateEventsAreMissing() {
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.FAILED, AiTaskStatus.RUNNING)).isTrue();
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.FAILED, AiTaskStatus.REVIEW_REQUIRED))
                .isTrue();
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.FAILED, AiTaskStatus.SUCCESS)).isTrue();
    }

    @Test
    void persistedReviewAndSuccessCannotRegress() {
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.REVIEW_REQUIRED, AiTaskStatus.WAITING))
                .isFalse();
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.REVIEW_REQUIRED, AiTaskStatus.RUNNING))
                .isFalse();
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.REVIEW_REQUIRED, AiTaskStatus.FAILED))
                .isFalse();
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.SUCCESS, AiTaskStatus.RUNNING)).isFalse();
        assertThat(AiTaskStatus.canTransition(AiTaskStatus.SUCCESS, AiTaskStatus.FAILED)).isFalse();
    }
}
