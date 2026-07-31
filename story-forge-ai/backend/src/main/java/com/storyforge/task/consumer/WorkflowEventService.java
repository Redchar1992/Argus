package com.storyforge.task.consumer;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.artifact.ArtifactInput;
import com.storyforge.artifact.StoryArtifactService;
import com.storyforge.cost.AiUsageRecorder;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryProjectMapper;
import com.storyforge.story.StoryStatus;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class WorkflowEventService {

    private final AiTaskMapper taskMapper;
    private final StoryProjectMapper storyMapper;
    private final StoryArtifactService artifactService;
    private final ObjectMapper objectMapper;
    private final AiUsageRecorder usage;

    public WorkflowEventService(
            AiTaskMapper taskMapper,
            StoryProjectMapper storyMapper,
            StoryArtifactService artifactService,
            ObjectMapper objectMapper,
            AiUsageRecorder usage
    ) {
        this.taskMapper = taskMapper;
        this.storyMapper = storyMapper;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
        this.usage = usage;
    }

    /**
     * Persists one Redis Stream event atomically. Returning normally means the
     * transaction committed and the listener may safely acknowledge the record.
     */
    @Transactional
    public boolean processEvent(String eventId, Map<String, String> fields) {
        Long taskId = requiredLong(fields, "taskId");
        // Serialize events for the same task across listener/reclaimer threads
        // and backend instances so the state-transition check cannot race.
        AiTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            throw new IllegalArgumentException("事件引用了不存在的 taskId: " + taskId);
        }
        if (StringUtils.hasText(task.getLastEventId())
                && compareStreamIds(eventId, task.getLastEventId()) <= 0) {
            return false;
        }

        Long storyId = optionalLong(fields.get("storyId"));
        if (storyId != null && !storyId.equals(task.getStoryId())) {
            throw new IllegalArgumentException("事件 storyId 与任务不一致");
        }
        String idempotencyKey = trimToNull(fields.get("idempotencyKey"));
        if (idempotencyKey != null && !idempotencyKey.equals(task.getIdempotencyKey())) {
            throw new IllegalArgumentException("事件 idempotencyKey 与任务不一致");
        }

        String threadId = trimToNull(fields.get("threadId"));
        if (threadId != null) {
            if (StringUtils.hasText(task.getThreadId()) && !threadId.equals(task.getThreadId())) {
                throw new IllegalArgumentException("事件 threadId 与任务不一致");
            }
        }

        String status = trimToNull(fields.get("status"));
        if (status != null) {
            status = status.toUpperCase(java.util.Locale.ROOT);
            if (!AiTaskStatus.isWorkflowStatus(status)) {
                throw new IllegalArgumentException("不支持的工作流状态: " + status);
            }
            if (!AiTaskStatus.canTransition(task.getStatus(), status)) {
                return false;
            }
            task.setStatus(status);
        }
        if (threadId != null) {
            task.setThreadId(threadId);
        }
        String currentNode = trimToNull(fields.get("currentNode"));
        if (currentNode != null) {
            task.setCurrentNode(truncate(currentNode, 64));
        }
        Integer progress = optionalInt(fields.get("progress"));
        if (progress != null) {
            task.setProgress(Math.max(0, Math.min(100, progress)));
        }
        Integer attemptNo = optionalInt(fields.get("attemptNo"));
        if (attemptNo != null) {
            task.setAttemptNo(Math.max(0, attemptNo));
        }

        if (fields.containsKey("errorCode")) {
            task.setErrorCode(truncate(trimToNull(fields.get("errorCode")), 64));
        }
        if (fields.containsKey("errorMessage")) {
            task.setErrorMessage(truncate(trimToNull(fields.get("errorMessage")), 1000));
        }
        Long inputTokens = optionalLong(fields.get("inputTokens"));
        Long outputTokens = optionalLong(fields.get("outputTokens"));
        Long durationMs = optionalLong(fields.get("durationMs"));
        if (inputTokens != null) {
            task.setInputTokens(inputTokens);
        }
        if (outputTokens != null) {
            task.setOutputTokens(outputTokens);
        }
        if (durationMs != null) {
            task.setDurationMs(durationMs);
        }
        String modelName = trimToNull(fields.get("modelName"));
        String promptVersion = trimToNull(fields.get("promptVersion"));
        if (modelName != null) {
            task.setModelName(truncate(modelName, 100));
        }
        if (promptVersion != null) {
            task.setPromptVersion(truncate(promptVersion, 32));
        }

        ArrayNode artifacts = parseArrayField(fields.get("artifacts"), "artifacts");
        persistArtifacts(task, artifacts);

        task.setResultPayload(writeJson(normalizedEvent(fields, artifacts)));
        task.setLastEventId(eventId);
        task.setUpdatedTime(LocalDateTime.now());
        taskMapper.updateById(task);
        updateStoryStatus(task);
        if (isTerminal(task.getStatus())) {
            usage.recordModelCalls(task, task.getTaskType(), fields.get("modelCalls"),
                    AiTaskStatus.SUCCESS.equals(task.getStatus()), task.getErrorCode());
        }
        return true;
    }

    private boolean isTerminal(String status) {
        return AiTaskStatus.SUCCESS.equals(status)
                || AiTaskStatus.FAILED.equals(status)
                || AiTaskStatus.REVIEW_REQUIRED.equals(status);
    }

    private void persistArtifacts(AiTask task, ArrayNode artifacts) {
        for (JsonNode value : artifacts) {
            if (!value.isObject()) {
                throw new IllegalArgumentException("artifacts 中的每一项必须是对象");
            }
            JsonNode content = value.get("content");
            if (content != null && content.isTextual()) {
                JsonNode parsed = parseJson(content.asText());
                content = parsed == null ? content : parsed;
            }
            artifactService.save(
                    task,
                    new ArtifactInput(
                            text(value, "artifactType"),
                            integer(value, "versionNo"),
                            text(value, "status"),
                            content,
                            text(value, "promptVersion"),
                            text(value, "modelName")
                    )
            );
        }
    }

    private ObjectNode normalizedEvent(Map<String, String> fields, ArrayNode artifacts) {
        ObjectNode result = objectMapper.createObjectNode();
        fields.forEach(result::put);
        result.set("artifacts", artifacts);
        result.set(
                "progressEvents",
                parseArrayField(fields.get("progressEvents"), "progressEvents")
        );
        return result;
    }

    private void updateStoryStatus(AiTask task) {
        Long latestTaskId = taskMapper.selectLatestWorkflowTaskId(task.getStoryId());
        if (!task.getId().equals(latestTaskId)) {
            return;
        }
        StoryProject story = storyMapper.selectById(task.getStoryId());
        if (story == null) {
            throw new IllegalStateException("工作流对应的故事不存在");
        }
        String storyStatus = switch (task.getStatus()) {
            case AiTaskStatus.RUNNING, AiTaskStatus.WAITING -> StoryStatus.WORKFLOW_RUNNING;
            case AiTaskStatus.REVIEW_REQUIRED -> StoryStatus.WORKFLOW_REVIEW_REQUIRED;
            case AiTaskStatus.SUCCESS -> StoryStatus.WORKFLOW_COMPLETED;
            case AiTaskStatus.FAILED -> StoryStatus.WORKFLOW_FAILED;
            default -> null;
        };
        if (storyStatus != null) {
            story.setStatus(storyStatus);
            story.setUpdatedTime(LocalDateTime.now());
            storyMapper.updateById(story);
        }
    }

    private ArrayNode parseArrayField(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            return objectMapper.createArrayNode();
        }
        JsonNode parsed = parseJson(raw);
        if (parsed == null || !parsed.isArray()) {
            throw new IllegalArgumentException(fieldName + " 必须是 JSON 数组");
        }
        return (ArrayNode) parsed;
    }

    private JsonNode parseJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("事件包含无效 JSON", exception);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化工作流事件", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private Long requiredLong(Map<String, String> fields, String field) {
        Long value = optionalLong(fields.get(field));
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private Long optionalLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("事件数字字段格式错误: " + value, exception);
        }
    }

    private Integer optionalInt(String value) {
        Long parsed = optionalLong(value);
        if (parsed == null) {
            return null;
        }
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("事件整数字段超出范围: " + value);
        }
        return parsed.intValue();
    }

    private int compareStreamIds(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        try {
            Iterator<String> leftParts = java.util.List.of(left.split("-", 2)).iterator();
            Iterator<String> rightParts = java.util.List.of(right.split("-", 2)).iterator();
            long leftTime = Long.parseLong(leftParts.next());
            long leftSequence = leftParts.hasNext() ? Long.parseLong(leftParts.next()) : 0;
            long rightTime = Long.parseLong(rightParts.next());
            long rightSequence = rightParts.hasNext() ? Long.parseLong(rightParts.next()) : 0;
            int timeComparison = Long.compare(leftTime, rightTime);
            return timeComparison != 0 ? timeComparison : Long.compare(leftSequence, rightSequence);
        } catch (RuntimeException ignored) {
            return left.compareTo(right);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
