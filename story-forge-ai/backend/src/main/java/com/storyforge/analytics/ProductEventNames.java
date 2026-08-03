package com.storyforge.analytics;

import java.util.List;
import java.util.Set;

public final class ProductEventNames {
    public static final String USER_REGISTERED = "USER_REGISTERED";
    public static final String USER_LOGIN_DAILY = "USER_LOGIN_DAILY";
    public static final String STORY_CREATED = "STORY_CREATED";
    public static final String TOPICS_GENERATED = "TOPICS_GENERATED";
    public static final String TOPIC_SELECTED = "TOPIC_SELECTED";
    public static final String OUTLINE_COMPLETED = "OUTLINE_COMPLETED";
    public static final String CHAPTER_APPROVED = "CHAPTER_APPROVED";
    public static final String THIRD_CHAPTER_APPROVED = "THIRD_CHAPTER_APPROVED";
    public static final String FINAL_REPORT_CREATED = "FINAL_REPORT_CREATED";
    public static final String RELEASE_CREATED = "RELEASE_CREATED";
    public static final String EXPORT_SUCCEEDED = "EXPORT_SUCCEEDED";
    public static final String FEEDBACK_SUBMITTED = "FEEDBACK_SUBMITTED";

    public static final List<String> FUNNEL = List.of(
            USER_REGISTERED,
            STORY_CREATED,
            TOPICS_GENERATED,
            TOPIC_SELECTED,
            OUTLINE_COMPLETED,
            CHAPTER_APPROVED,
            THIRD_CHAPTER_APPROVED,
            FINAL_REPORT_CREATED,
            EXPORT_SUCCEEDED,
            FEEDBACK_SUBMITTED
    );

    private static final Set<String> ALLOWED = Set.of(
            USER_REGISTERED,
            USER_LOGIN_DAILY,
            STORY_CREATED,
            TOPICS_GENERATED,
            TOPIC_SELECTED,
            OUTLINE_COMPLETED,
            CHAPTER_APPROVED,
            THIRD_CHAPTER_APPROVED,
            FINAL_REPORT_CREATED,
            RELEASE_CREATED,
            EXPORT_SUCCEEDED,
            FEEDBACK_SUBMITTED
    );

    private ProductEventNames() {
    }

    public static boolean isAllowed(String value) {
        return ALLOWED.contains(value);
    }
}
