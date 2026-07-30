package com.storyforge.story;

public final class StoryStatus {

    public static final String DRAFT = "DRAFT";
    public static final String GENERATING = "GENERATING";
    public static final String GENERATED = "GENERATED";
    public static final String GENERATION_FAILED = "GENERATION_FAILED";
    public static final String SELECTED = "SELECTED";
    public static final String WORKFLOW_RUNNING = "WORKFLOW_RUNNING";
    public static final String WORKFLOW_REVIEW_REQUIRED = "WORKFLOW_REVIEW_REQUIRED";
    public static final String WORKFLOW_COMPLETED = "WORKFLOW_COMPLETED";
    public static final String WORKFLOW_FAILED = "WORKFLOW_FAILED";

    private StoryStatus() {
    }
}
