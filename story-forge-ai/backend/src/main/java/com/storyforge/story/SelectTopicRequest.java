package com.storyforge.story;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record SelectTopicRequest(
        @NotNull(message = "topicId 不能为空")
        JsonNode topicId
) {
}
