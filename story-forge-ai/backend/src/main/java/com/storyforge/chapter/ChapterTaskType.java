package com.storyforge.chapter;

public final class ChapterTaskType {
    public static final String PLAN = "CHAPTER_PLAN";
    public static final String GENERATE = "CHAPTER_GENERATE";
    public static final String REWRITE = "CHAPTER_REWRITE";
    public static final String FINALIZE = "CHAPTER_FINALIZE";
    public static boolean isChapterTask(String type) {
        return PLAN.equals(type) || GENERATE.equals(type) || REWRITE.equals(type) || FINALIZE.equals(type);
    }
    private ChapterTaskType() { }
}
