package com.storyforge.release;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

public record ReleaseResponse(
        Long id,
        Long storyId,
        Integer releaseNo,
        String title,
        String summary,
        JsonNode tags,
        Long outlineVersionId,
        Long reportId,
        JsonNode chapterVersions,
        JsonNode characters,
        JsonNode outline,
        Integer wordCount,
        String contentHash,
        String status,
        LocalDateTime createdTime
) { }
