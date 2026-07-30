package com.storyforge.workflow.vo;

import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowReviewResponse(
        Long taskId,
        Long storyId,
        String threadId,
        String status,
        Integer revisionCount,
        JsonNode characters,
        JsonNode outline,
        JsonNode score,
        JsonNode versions
) {
}
