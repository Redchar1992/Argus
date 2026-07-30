package com.storyforge.chapter.vo;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;

public record ChapterResponse(
        Long id,
        Long storyId,
        Integer chapterNo,
        String title,
        String status,
        String planStatus,
        JsonNode plan,
        String planHash,
        Integer wordCount,
        Long rowVersion,
        Long currentVersionId,
        ChapterVersionResponse currentVersion,
        Long activeTaskId,
        String activeTaskStatus,
        String activeTaskType,
        JsonNode summary,
        LocalDateTime approvedTime,
        LocalDateTime createdTime,
        LocalDateTime updatedTime
) { }
