package com.storyforge.report;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.JsonNode;

public record FinalReportResponse(
        Long id,
        Long storyId,
        Integer versionNo,
        String status,
        JsonNode report,
        Integer total,
        String level,
        Integer wordCount,
        String contentHash,
        String promptVersion,
        String modelName,
        LocalDateTime createdTime
) { }
