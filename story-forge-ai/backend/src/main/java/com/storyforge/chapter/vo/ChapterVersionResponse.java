package com.storyforge.chapter.vo;

import java.time.LocalDateTime;
import com.fasterxml.jackson.databind.JsonNode;

public record ChapterVersionResponse(
        Long id,
        Long chapterId,
        Integer versionNo,
        String sourceType,
        String content,
        String contentHash,
        Long baseVersionId,
        Long aiTaskId,
        String promptVersion,
        String modelName,
        JsonNode review,
        String changeSummary,
        Long createdBy,
        LocalDateTime createdTime
) { }
