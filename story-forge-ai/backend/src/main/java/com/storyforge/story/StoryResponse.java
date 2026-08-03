package com.storyforge.story;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

public record StoryResponse(
        Long id,
        Long userId,
        String title,
        String genre,
        String audience,
        String keywords,
        String status,
        JsonNode selectedTopic,
        JsonNode generatedTopics,
        String contentMode,
        Integer targetChapterCount,
        Integer targetTotalWords,
        Integer chapterTargetWords,
        String viewpoint,
        JsonNode styleProfile,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
    public StoryResponse(
            Long id,
            Long userId,
            String title,
            String genre,
            String audience,
            String keywords,
            String status,
            JsonNode selectedTopic,
            JsonNode generatedTopics,
            LocalDateTime createdTime,
            LocalDateTime updatedTime
    ) {
        this(id, userId, title, genre, audience, keywords, status, selectedTopic,
                generatedTopics, "SHORT_STORY", 10, 30_000, 1_800,
                "THIRD_LIMITED", null, createdTime, updatedTime);
    }
}
