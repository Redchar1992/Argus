package com.storyforge.workflow.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record StartWorkflowRequest(
        @NotNull(message = "topicId 不能为空")
        JsonNode topicId
) {
}
