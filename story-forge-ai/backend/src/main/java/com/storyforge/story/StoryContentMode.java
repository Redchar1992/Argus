package com.storyforge.story;

import java.util.Locale;

/**
 * First-class content profiles keep short-story defaults compatible while
 * allowing novel projects to use a larger, slower-moving outline.
 */
public enum StoryContentMode {
    SHORT_STORY(3, 10, 30_000, 1_800),
    NOVEL(20, 30, 300_000, 2_500);

    private final int minChapterCount;
    private final int defaultChapterCount;
    private final int defaultTotalWords;
    private final int defaultChapterWords;

    StoryContentMode(int minChapterCount, int defaultChapterCount, int defaultTotalWords, int defaultChapterWords) {
        this.minChapterCount = minChapterCount;
        this.defaultChapterCount = defaultChapterCount;
        this.defaultTotalWords = defaultTotalWords;
        this.defaultChapterWords = defaultChapterWords;
    }

    public int defaultChapterCount() {
        return defaultChapterCount;
    }

    public int minChapterCount() {
        return minChapterCount;
    }

    public int defaultTotalWords() {
        return defaultTotalWords;
    }

    public int defaultChapterWords() {
        return defaultChapterWords;
    }

    public static StoryContentMode parse(String value) {
        if (value == null || value.isBlank()) return SHORT_STORY;
        try {
            return value.trim().toUpperCase(Locale.ROOT).equals("SHORT")
                    ? SHORT_STORY
                    : value.trim().toUpperCase(Locale.ROOT).equals("LONG")
                            ? NOVEL
                            : valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("contentMode 必须是 SHORT_STORY 或 NOVEL");
        }
    }
}
