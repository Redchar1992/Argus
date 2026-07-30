package com.storyforge.workflow.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.artifact.ArtifactType;
import com.storyforge.artifact.StoryArtifact;
import com.storyforge.artifact.StoryArtifactService;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.SelectTopicRequest;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryResponse;
import com.storyforge.story.StoryService;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskService;
import com.storyforge.task.AiTaskStatus;
import com.storyforge.task.producer.WorkflowDispatchException;
import com.storyforge.task.producer.WorkflowRequestPublisher;
import com.storyforge.workflow.dto.ReviewDecisionRequest;
import com.storyforge.workflow.dto.StartWorkflowRequest;
import com.storyforge.workflow.vo.WorkflowReviewResponse;
import com.storyforge.workflow.vo.WorkflowTaskCreatedResponse;
import com.storyforge.workflow.vo.WorkflowTaskStatusResponse;

import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WorkflowService {

    private final StoryService storyService;
    private final AiTaskService taskService;
    private final StoryArtifactService artifactService;
    private final WorkflowTaskPersistenceService taskPersistenceService;
    private final WorkflowRequestPublisher requestPublisher;
    private final ObjectMapper objectMapper;

    public WorkflowService(
            StoryService storyService,
            AiTaskService taskService,
            StoryArtifactService artifactService,
            WorkflowTaskPersistenceService taskPersistenceService,
            WorkflowRequestPublisher requestPublisher,
            ObjectMapper objectMapper
    ) {
        this.storyService = storyService;
        this.taskService = taskService;
        this.artifactService = artifactService;
        this.taskPersistenceService = taskPersistenceService;
        this.requestPublisher = requestPublisher;
        this.objectMapper = objectMapper;
    }

    public WorkflowTaskCreatedResponse start(
            Long userId,
            Long storyId,
            StartWorkflowRequest request
    ) {
        StoryProject story = storyService.requireOwned(userId, storyId);
        String idempotencyKey = WorkflowTaskPersistenceService.startIdempotencyKey(storyId);
        AiTask task = taskService.findByIdempotencyKey(userId, idempotencyKey);
        boolean creationAttempted = task == null;
        if (creationAttempted) {
            StoryResponse selected = storyService.selectTopic(
                    userId,
                    storyId,
                    new SelectTopicRequest(request.topicId())
            );
            task = createStartTaskIdempotently(
                    userId,
                    story,
                    selected.selectedTopic(),
                    idempotencyKey
            );
        }

        // The unique-key fallback may return a task won by another concurrent
        // request. The persisted task payload is the sole binding authority.
        JsonNode topic = readRequestPayload(task).path("topic");
        if (creationAttempted) {
            storyService.restoreWorkflowSelection(userId, storyId, task.getId(), topic);
        }
        assertWorkflowTopicMatches(request.topicId(), topic);

        if (shouldDispatch(task)) {
            if (AiTaskStatus.FAILED.equals(task.getStatus())) {
                taskPersistenceService.prepareDispatchRetry(task);
            }
            publish(task, "START", topic, null, "");
        }
        return new WorkflowTaskCreatedResponse(task.getId(), task.getStatus());
    }

    public WorkflowTaskStatusResponse getTask(Long userId, Long taskId) {
        AiTask task = taskService.requireOwned(userId, taskId);
        return toTaskStatusResponse(task);
    }

    public WorkflowTaskStatusResponse getLatestTask(Long userId, Long storyId) {
        storyService.requireOwned(userId, storyId);
        AiTask task = taskService.findLatestForStory(
                userId,
                storyId,
                List.of(
                        WorkflowTaskPersistenceService.TASK_TYPE_START,
                        WorkflowTaskPersistenceService.TASK_TYPE_RESUME
                )
        );
        if (task == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "WORKFLOW_TASK_NOT_FOUND",
                    "故事尚未创建工作流任务"
            );
        }
        return toTaskStatusResponse(task);
    }

    private WorkflowTaskStatusResponse toTaskStatusResponse(AiTask task) {
        StoryArtifact scoreArtifact = artifactService.findLatest(task.getStoryId(), ArtifactType.SCORE);
        StoryArtifact outlineArtifact = artifactService.findLatest(task.getStoryId(), ArtifactType.OUTLINE);
        JsonNode scoreContent = artifactService.content(scoreArtifact);
        StoryArtifact finalArtifact = null;
        if (AiTaskStatus.SUCCESS.equals(task.getStatus())) {
            finalArtifact = artifactService.findLatest(task.getStoryId(), ArtifactType.WORKFLOW_FINAL);
            JsonNode finalScore = child(artifactService.content(finalArtifact), "score");
            if (finalScore != null) {
                scoreContent = finalScore;
            }
        }
        JsonNode taskResult = parseJson(task.getResultPayload());
        return new WorkflowTaskStatusResponse(
                task.getId(),
                task.getStoryId(),
                originalWorkflowTopicId(task),
                task.getStatus(),
                task.getCurrentNode(),
                task.getProgress(),
                task.getThreadId(),
                scoreTotal(scoreContent),
                integerValue(
                        taskResult,
                        "revisionCount",
                        finalArtifact == null
                                ? revisionCount(outlineArtifact)
                                : Math.max(0, finalArtifact.getVersionNo() - 1)
                ),
                integerValue(taskResult, "maxRevisions", 2),
                progressEvents(task),
                task.getErrorCode(),
                task.getErrorMessage()
        );
    }

    private JsonNode originalWorkflowTopicId(AiTask task) {
        AiTask startTask = taskService.findByIdempotencyKey(
                task.getUserId(),
                WorkflowTaskPersistenceService.startIdempotencyKey(task.getStoryId())
        );
        if (startTask == null) {
            return null;
        }
        JsonNode topic = readRequestPayload(startTask).get("topic");
        if (topic == null || !topic.isObject()) {
            throw new IllegalStateException("原始工作流任务缺少 topic");
        }
        JsonNode topicId = topic.get("id");
        if (topicId == null || topicId.isNull() || topicId.isContainerNode()) {
            throw new IllegalStateException("原始工作流选题缺少有效 id");
        }
        return topicId;
    }

    private void assertWorkflowTopicMatches(JsonNode requestedTopicId, JsonNode storedTopic) {
        String requested = scalarTopicId(requestedTopicId, false);
        JsonNode storedTopicId = storedTopic == null || !storedTopic.isObject()
                ? null
                : storedTopic.get("id");
        String stored = scalarTopicId(storedTopicId, true);
        if (!requested.equals(stored)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "WORKFLOW_TOPIC_LOCKED",
                    "工作流已绑定其他选题，不能重新开始"
            );
        }
    }

    private String scalarTopicId(JsonNode value, boolean persisted) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            if (persisted) {
                throw new IllegalStateException("原始工作流选题缺少有效 id");
            }
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_TOPIC_ID",
                    "topicId 必须是数字或字符串"
            );
        }
        return value.asText();
    }

    public WorkflowReviewResponse getReview(Long userId, Long taskId) {
        AiTask task = taskService.requireOwned(userId, taskId);
        if (!AiTaskStatus.REVIEW_REQUIRED.equals(task.getStatus())
                && !AiTaskStatus.SUCCESS.equals(task.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REVIEW_NOT_READY",
                    "工作流尚未进入审核阶段"
            );
        }

        MatchedArtifacts matched = findLatestMatchedArtifacts(task.getStoryId());
        StoryArtifact finalArtifact = AiTaskStatus.SUCCESS.equals(task.getStatus())
                ? artifactService.findLatest(task.getStoryId(), ArtifactType.WORKFLOW_FINAL)
                : null;
        JsonNode finalContent = artifactService.content(finalArtifact);

        JsonNode characterContent = child(finalContent, "characters");
        if (characterContent == null) {
            StoryArtifact characterArtifact = requireArtifact(task, ArtifactType.CHARACTER, "人物卡");
            characterContent = artifactService.content(characterArtifact);
        }

        JsonNode outlineContent = child(finalContent, "outline");
        JsonNode scoreContent = child(finalContent, "score");
        if (outlineContent == null || scoreContent == null) {
            matched = requireMatchedArtifacts(matched);
            if (outlineContent == null) {
                outlineContent = artifactService.content(matched.outline());
            }
            if (scoreContent == null) {
                scoreContent = artifactService.content(matched.score());
            }
        }

        JsonNode displayedOutline = finalArtifact != null && child(finalContent, "outline") != null
                ? finalOutline(finalContent)
                : fullOutline(outlineContent);
        JsonNode taskResult = parseJson(task.getResultPayload());
        int displayedVersion = finalArtifact != null
                ? finalArtifact.getVersionNo()
                : matched.versionNo();
        return new WorkflowReviewResponse(
                task.getId(),
                task.getStoryId(),
                task.getThreadId(),
                task.getStatus(),
                integerValue(taskResult, "revisionCount", Math.max(0, displayedVersion - 1)),
                unwrapArray(characterContent, "characters"),
                displayedOutline,
                unwrapObject(scoreContent, "score"),
                outlineVersions(task.getStoryId())
        );
    }

    public WorkflowTaskCreatedResponse review(
            Long userId,
            Long taskId,
            ReviewDecisionRequest request
    ) {
        AiTask source = taskService.requireOwned(userId, taskId);
        if (!AiTaskStatus.REVIEW_REQUIRED.equals(source.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REVIEW_NOT_REQUIRED",
                    "当前任务不在人工审核阶段"
            );
        }
        if (!StringUtils.hasText(source.getThreadId())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "WORKFLOW_THREAD_MISSING",
                    "任务缺少可恢复的 threadId"
            );
        }
        String notes = request.notes() == null ? "" : request.notes().trim();
        if (!request.approved() && !StringUtils.hasText(notes)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "REVIEW_NOTES_REQUIRED",
                    "要求修改时必须填写具体修改意见"
            );
        }

        ReviewDecisionRequest normalizedRequest = new ReviewDecisionRequest(request.approved(), notes);
        AiTask task = createResumeTaskIdempotently(source, normalizedRequest);
        if (shouldDispatch(task)) {
            if (AiTaskStatus.FAILED.equals(task.getStatus())) {
                taskPersistenceService.prepareDispatchRetry(task);
            }
            JsonNode payload = readRequestPayload(task);
            publish(
                    task,
                    "RESUME",
                    null,
                    payload.path("approved").asBoolean(),
                    payload.path("notes").asText("")
            );
        }
        return new WorkflowTaskCreatedResponse(task.getId(), task.getStatus());
    }

    private void publish(
            AiTask task,
            String action,
            JsonNode topic,
            Boolean approved,
            String notes
    ) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("taskId", task.getId().toString());
        fields.put("storyId", task.getStoryId().toString());
        fields.put("threadId", task.getThreadId() == null ? "" : task.getThreadId());
        fields.put("action", action);
        fields.put("payloadVersion", "1");
        fields.put("idempotencyKey", task.getIdempotencyKey());
        fields.put("topic", topic == null || topic.isMissingNode() ? "" : writeJson(topic));
        fields.put("approved", approved == null ? "" : approved.toString());
        fields.put("notes", notes == null ? "" : notes);
        try {
            requestPublisher.publish(fields);
        } catch (WorkflowDispatchException exception) {
            taskPersistenceService.markDispatchFailed(task, exception.getMessage());
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    WorkflowTaskPersistenceService.DISPATCH_ERROR_CODE,
                    "工作流暂时不可用：" + exception.getMessage()
            );
        }
    }

    private AiTask createStartTaskIdempotently(
            Long userId,
            StoryProject story,
            JsonNode topic,
            String idempotencyKey
    ) {
        try {
            return taskPersistenceService.createStartTask(userId, story, topic);
        } catch (DataIntegrityViolationException exception) {
            AiTask existing = taskService.findByIdempotencyKey(userId, idempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw exception;
        }
    }

    private AiTask createResumeTaskIdempotently(
            AiTask source,
            ReviewDecisionRequest request
    ) {
        int resumeNo = WorkflowTaskPersistenceService.nextResumeNo(source);
        String idempotencyKey = WorkflowTaskPersistenceService.resumeIdempotencyKey(
                source.getStoryId(),
                resumeNo
        );
        try {
            return taskPersistenceService.createResumeTask(source, request);
        } catch (DataIntegrityViolationException exception) {
            AiTask existing = taskService.findByIdempotencyKey(source.getUserId(), idempotencyKey);
            if (existing != null) {
                return existing;
            }
            throw exception;
        }
    }

    private boolean shouldDispatch(AiTask task) {
        return AiTaskStatus.WAITING.equals(task.getStatus())
                || (AiTaskStatus.FAILED.equals(task.getStatus())
                && WorkflowTaskPersistenceService.DISPATCH_ERROR_CODE.equals(task.getErrorCode()));
    }

    private StoryArtifact requireArtifact(AiTask task, String type, String displayName) {
        StoryArtifact artifact = artifactService.findLatest(task.getStoryId(), type);
        if (artifact == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ARTIFACT_MISSING",
                    displayName + "产物尚未保存"
            );
        }
        return artifact;
    }

    private MatchedArtifacts requireMatchedArtifacts(MatchedArtifacts matched) {
        if (matched == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ARTIFACT_VERSION_MISMATCH",
                    "尚无同版本的大纲与评分产物"
            );
        }
        return matched;
    }

    private JsonNode progressEvents(AiTask task) {
        JsonNode result = parseJson(task.getResultPayload());
        JsonNode value = result == null ? null : result.get("progressEvents");
        if (value != null && value.isTextual()) {
            value = parseJson(value.asText());
        }
        return value != null && value.isArray() ? value : objectMapper.createArrayNode();
    }

    private Integer scoreTotal(JsonNode score) {
        if (score == null) {
            return null;
        }
        JsonNode value = score.has("total") ? score.get("total") : score.path("score").get("total");
        return value != null && value.canConvertToInt() ? value.asInt() : null;
    }

    private JsonNode unwrapArray(JsonNode content, String... keys) {
        if (content != null && content.isArray()) {
            return content;
        }
        if (content != null) {
            for (String key : keys) {
                JsonNode nested = content.get(key);
                if (nested != null && nested.isArray()) {
                    return nested;
                }
            }
        }
        return objectMapper.createArrayNode();
    }

    private JsonNode unwrapObject(JsonNode content, String key) {
        if (content != null && content.isObject() && content.has(key) && content.get(key).isObject()) {
            return content.get(key);
        }
        return content == null ? objectMapper.createObjectNode() : content;
    }

    private JsonNode fullOutline(JsonNode content) {
        if (content == null) {
            return objectMapper.createObjectNode();
        }
        if (content.isObject() && content.has("outline") && content.get("outline").isObject()) {
            return content.get("outline");
        }
        if (content.isArray()) {
            ObjectNode wrapped = objectMapper.createObjectNode();
            wrapped.set("nodes", content);
            return wrapped;
        }
        return content;
    }

    private JsonNode finalOutline(JsonNode finalContent) {
        JsonNode outline = child(finalContent, "outline");
        if (outline == null) {
            return objectMapper.createObjectNode();
        }
        if (!outline.isArray()) {
            return fullOutline(outline);
        }

        ObjectNode wrapped = objectMapper.createObjectNode();
        JsonNode metadata = child(finalContent, "outlineMetadata");
        if (metadata == null) {
            metadata = child(finalContent, "outline_metadata");
        }
        if (metadata != null && metadata.isObject()) {
            metadata.fields().forEachRemaining(entry -> wrapped.set(entry.getKey(), entry.getValue()));
        }
        wrapped.set("nodes", outline);
        return wrapped;
    }

    private JsonNode child(JsonNode content, String field) {
        if (content == null || !content.isObject()) {
            return null;
        }
        JsonNode value = content.get(field);
        return value == null || value.isNull() ? null : value;
    }

    private MatchedArtifacts findLatestMatchedArtifacts(Long storyId) {
        Map<Integer, StoryArtifact> scores = artifactService
                .listVersions(storyId, ArtifactType.SCORE)
                .stream()
                .collect(Collectors.toMap(
                        StoryArtifact::getVersionNo,
                        Function.identity(),
                        (first, ignored) -> first
                ));
        return artifactService.listVersions(storyId, ArtifactType.OUTLINE).stream()
                .filter(outline -> scores.containsKey(outline.getVersionNo()))
                .max(java.util.Comparator.comparing(StoryArtifact::getVersionNo))
                .map(outline -> new MatchedArtifacts(
                        outline.getVersionNo(),
                        outline,
                        scores.get(outline.getVersionNo())
                ))
                .orElse(null);
    }

    private JsonNode outlineVersions(Long storyId) {
        List<StoryArtifact> outlines = artifactService.listVersions(storyId, ArtifactType.OUTLINE);
        Map<Integer, StoryArtifact> scores = artifactService
                .listVersions(storyId, ArtifactType.SCORE)
                .stream()
                .collect(Collectors.toMap(
                        StoryArtifact::getVersionNo,
                        Function.identity(),
                        (first, ignored) -> first
                ));
        var versions = objectMapper.createArrayNode();
        for (StoryArtifact outline : outlines) {
            StoryArtifact score = scores.get(outline.getVersionNo());
            if (score == null) {
                continue;
            }
            ObjectNode item = versions.addObject();
            item.put("versionNo", outline.getVersionNo());
            item.put("status", outline.getStatus());
            item.put("taskId", outline.getTaskId());
            item.put("createdTime", outline.getCreatedTime().toString());
            item.set("outline", fullOutline(artifactService.content(outline)));
            item.set("score", unwrapObject(artifactService.content(score), "score"));
        }
        return versions;
    }

    private record MatchedArtifacts(
            int versionNo,
            StoryArtifact outline,
            StoryArtifact score
    ) {
    }

    private Integer integerValue(JsonNode object, String field, Integer fallback) {
        if (object == null) {
            return fallback;
        }
        JsonNode value = object.get(field);
        if (value != null && value.isTextual()) {
            try {
                return Integer.valueOf(value.asText());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private Integer revisionCount(StoryArtifact outlineArtifact) {
        return outlineArtifact == null ? 0 : Math.max(0, outlineArtifact.getVersionNo() - 1);
    }

    private JsonNode readRequestPayload(AiTask task) {
        JsonNode payload = parseJson(task.getRequestPayload());
        if (payload == null || !payload.isObject()) {
            throw new IllegalStateException("工作流任务请求 JSON 无效");
        }
        return payload;
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的工作流 JSON 无效", exception);
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化工作流消息", exception);
        }
    }
}
