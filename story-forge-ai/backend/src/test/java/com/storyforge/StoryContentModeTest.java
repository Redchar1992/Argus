package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.storyforge.chapter.service.ChapterTaskService;
import com.storyforge.story.StoryContentMode;

class StoryContentModeTest {

    @Test
    void parsesCanonicalAndUiAliasesWithProfileDefaults() {
        assertThat(StoryContentMode.parse("SHORT_STORY")).isEqualTo(StoryContentMode.SHORT_STORY);
        assertThat(StoryContentMode.parse("short")).isEqualTo(StoryContentMode.SHORT_STORY);
        assertThat(StoryContentMode.parse("LONG")).isEqualTo(StoryContentMode.NOVEL);
        assertThat(StoryContentMode.NOVEL.defaultChapterCount()).isEqualTo(30);
        assertThat(StoryContentMode.NOVEL.defaultChapterWords()).isEqualTo(2_500);
    }

    @Test
    void rejectsUnknownContentModes() {
        assertThatThrownBy(() -> StoryContentMode.parse("SCRIPT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SHORT_STORY");
    }

    @Test
    void chapterCreditCostsMatchTheVisibleAiOperations() {
        assertThat(ChapterTaskService.creditCost("CHAPTER_PLAN")).isEqualTo(3L);
        assertThat(ChapterTaskService.creditCost("CHAPTER_GENERATE")).isEqualTo(12L);
        assertThat(ChapterTaskService.creditCost("CHAPTER_REWRITE")).isEqualTo(6L);
        assertThat(ChapterTaskService.creditCost("CHAPTER_FINALIZE")).isEqualTo(5L);
    }
}
