package com.storyforge.chapter.vo;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;

public record TaskEventResponse(
        String eventId,
        Long taskId,
        Long storyId,
        Long chapterId,
        Integer chapterNo,
        String type,
        Long sequence,
        String status,
        String currentNode,
        Integer progress,
        JsonNode data,
        String errorCode,
        String errorMessage,
        LocalDateTime createdTime
) { }
