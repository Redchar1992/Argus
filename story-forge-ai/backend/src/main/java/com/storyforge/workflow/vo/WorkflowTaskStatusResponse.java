package com.storyforge.workflow.vo;

import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowTaskStatusResponse(
        Long taskId,
        Long storyId,
        JsonNode topicId,
        String status,
        String currentNode,
        Integer progress,
        String threadId,
        Integer score,
        Integer revisionCount,
        Integer maxRevisions,
        JsonNode progressEvents,
        String errorCode,
        String errorMessage,
        String taskType,
        Long chapterId,
        JsonNode result
) {
}
