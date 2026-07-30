package com.storyforge.workflow.service;

import java.time.LocalDateTime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.story.StoryProject;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskService;
import com.storyforge.task.AiTaskStatus;
import com.storyforge.workflow.dto.ReviewDecisionRequest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowTaskPersistenceService {

    public static final String TASK_TYPE_START = "STORY_WORKFLOW";
    public static final String TASK_TYPE_RESUME = "WORKFLOW_RESUME";
    public static final String DISPATCH_ERROR_CODE = "WORKFLOW_QUEUE_UNAVAILABLE";

    private final AiTaskMapper taskMapper;
    private final AiTaskService taskService;
    private final ObjectMapper objectMapper;

    public WorkflowTaskPersistenceService(
            AiTaskMapper taskMapper,
            AiTaskService taskService,
            ObjectMapper objectMapper
    ) {
        this.taskMapper = taskMapper;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiTask createStartTask(Long userId, StoryProject story, JsonNode topic) {
        String idempotencyKey = startIdempotencyKey(story.getId());
        AiTask existing = taskService.findByIdempotencyKey(userId, idempotencyKey);
        if (existing != null) {
            return existing;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "START");
        payload.set("topic", topic);

        AiTask task = newTask(userId, story.getId());
        task.setTaskType(TASK_TYPE_START);
        task.setStatus(AiTaskStatus.WAITING);
        task.setCurrentNode("queued");
        task.setProgress(0);
        task.setAttemptNo(0);
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestPayload(writeJson(payload));
        taskMapper.insert(task);
        return task;
    }

    @Transactional
    public AiTask createResumeTask(AiTask source, ReviewDecisionRequest request) {
        int resumeNo = nextResumeNo(source);
        String idempotencyKey = resumeIdempotencyKey(source.getStoryId(), resumeNo);
        AiTask existing = taskService.findByIdempotencyKey(source.getUserId(), idempotencyKey);
        if (existing != null) {
            return existing;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "RESUME");
        payload.put("approved", request.approved());
        payload.put("notes", request.notes() == null ? "" : request.notes().trim());
        payload.put("sourceTaskId", source.getId());

        AiTask task = newTask(source.getUserId(), source.getStoryId());
        task.setTaskType(TASK_TYPE_RESUME);
        task.setStatus(AiTaskStatus.WAITING);
        task.setThreadId(source.getThreadId());
        task.setCurrentNode("queued_resume");
        task.setProgress(source.getProgress() == null ? 85 : source.getProgress());
        task.setAttemptNo(0);
        task.setParentTaskId(source.getId());
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestPayload(writeJson(payload));
        taskMapper.insert(task);
        return task;
    }

    @Transactional
    public void prepareDispatchRetry(AiTask task) {
        task.setStatus(AiTaskStatus.WAITING);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setUpdatedTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    @Transactional
    public void markDispatchFailed(AiTask task, String message) {
        task.setStatus(AiTaskStatus.FAILED);
        task.setErrorCode(DISPATCH_ERROR_CODE);
        task.setErrorMessage(truncate(message, 1000));
        task.setUpdatedTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    public static String startIdempotencyKey(Long storyId) {
        return storyId + ":v1:START:0";
    }

    public static String resumeIdempotencyKey(Long storyId, int attemptNo) {
        return storyId + ":v1:RESUME:" + attemptNo;
    }

    public static int nextResumeNo(AiTask source) {
        String key = source.getIdempotencyKey();
        if (key != null && key.endsWith(":START:0")) {
            return 1;
        }
        String marker = ":RESUME:";
        int markerIndex = key == null ? -1 : key.lastIndexOf(marker);
        if (markerIndex >= 0) {
            try {
                return Integer.parseInt(key.substring(markerIndex + marker.length())) + 1;
            } catch (NumberFormatException exception) {
                throw new IllegalStateException("源任务的 RESUME 幂等键无效", exception);
            }
        }
        throw new IllegalStateException("源任务缺少工作流幂等键");
    }

    private AiTask newTask(Long userId, Long storyId) {
        LocalDateTime now = LocalDateTime.now();
        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setStoryId(storyId);
        task.setCreatedTime(now);
        task.setUpdatedTime(now);
        return task;
    }

    private String writeJson(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化工作流请求", exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
