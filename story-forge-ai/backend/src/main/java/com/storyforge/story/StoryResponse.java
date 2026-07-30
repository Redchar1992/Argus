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
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) {
}
