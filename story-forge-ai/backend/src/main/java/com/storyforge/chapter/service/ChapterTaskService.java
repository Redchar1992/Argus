package com.storyforge.chapter.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.chapter.stream.ChapterCommandPublisher;
import com.storyforge.chapter.ChapterTaskType;
import com.storyforge.chapter.vo.ChapterTaskResponse;
import com.storyforge.common.exception.ApiException;
import com.storyforge.cost.AiCreditService;
import com.storyforge.cost.AiPricingService;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskStatus;
import com.storyforge.task.producer.WorkflowDispatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChapterTaskService {
    public static final String DISPATCH_ERROR = "CHAPTER_QUEUE_UNAVAILABLE";
    private final AiTaskMapper tasks;
    private final ChapterCommandPublisher publisher;
    private final ChapterSupport support;
    private final AiCreditService credits;
    private final AiPricingService pricing;
    public ChapterTaskService(AiTaskMapper tasks, ChapterCommandPublisher publisher, ChapterSupport support,
            AiCreditService credits, AiPricingService pricing) {
        this.tasks = tasks; this.publisher = publisher; this.support = support; this.credits = credits;
        this.pricing = pricing;
    }

    @Transactional
    public AiTask create(Long userId, Long storyId, Long chapterId, String taskType,
                         String action, String idempotencyKey, JsonNode payload, Long parentTaskId) {
        return create(userId, storyId, chapterId, taskType, action, idempotencyKey, payload, parentTaskId, null);
    }

    @Transactional
    public AiTask create(Long userId, Long storyId, Long chapterId, String taskType,
                         String action, String idempotencyKey, JsonNode payload, Long parentTaskId,
                         String threadId) {
        AiTask existing = find(userId, idempotencyKey);
        if (existing == null) {
            return insert(userId, storyId, chapterId, taskType, action, idempotencyKey, payload,
                    parentTaskId, threadId, 0);
        }
        while (AiTaskStatus.FAILED.equals(existing.getStatus())) {
            int attemptNo = existing.getAttemptNo() == null ? 1 : existing.getAttemptNo() + 1;
            String retryKey = retryKey(idempotencyKey, attemptNo);
            AiTask retry = find(userId, retryKey);
            if (retry == null) {
                return insert(userId, storyId, chapterId, taskType, action, retryKey, payload,
                        existing.getId(), existing.getThreadId(), attemptNo);
            }
            existing = retry;
        }
        return existing;
    }

    private AiTask insert(Long userId, Long storyId, Long chapterId, String taskType,
                          String action, String idempotencyKey, JsonNode payload, Long parentTaskId,
                          String threadId, int attemptNo) {
        LocalDateTime now = LocalDateTime.now();
        AiTask task = new AiTask();
        task.setUserId(userId); task.setStoryId(storyId); task.setChapterId(chapterId);
        task.setTaskType(taskType); task.setStatus(AiTaskStatus.WAITING);
        task.setRequestPayload(requestPayload(action, payload));
        task.setThreadId(threadId == null ? UUID.randomUUID().toString() : threadId);
        task.setCurrentNode("queued"); task.setProgress(0); task.setAttemptNo(attemptNo);
        task.setParentTaskId(parentTaskId); task.setIdempotencyKey(idempotencyKey);
        task.setCreatedTime(now); task.setUpdatedTime(now);
        try { tasks.insert(task); }
        catch (DataIntegrityViolationException exception) {
            AiTask raced = find(userId, idempotencyKey);
            if (raced != null) return raced;
            throw exception;
        }
        credits.freeze(userId, task.getId(), freezeKey(task), configuredCreditCost(taskType),
                "章节 AI 任务预冻结：" + taskType);
        return task;
    }

    public ChapterTaskResponse dispatch(AiTask task, Integer chapterNo, String action, JsonNode payload) {
        if (AiTaskStatus.SUCCESS.equals(task.getStatus()) || AiTaskStatus.REVIEW_REQUIRED.equals(task.getStatus())
                || AiTaskStatus.RUNNING.equals(task.getStatus())) {
            return response(task);
        }
        if (AiTaskStatus.FAILED.equals(task.getStatus())) return response(task);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("taskId", task.getId().toString());
        fields.put("storyId", task.getStoryId().toString());
        fields.put("chapterId", task.getChapterId().toString());
        fields.put("chapterNo", chapterNo.toString());
        fields.put("action", action);
        fields.put("threadId", task.getThreadId());
        fields.put("idempotencyKey", task.getIdempotencyKey());
        fields.put("payload", support.write(payload));
        try { publisher.publish(fields); }
        catch (WorkflowDispatchException exception) {
            markFailed(task, exception.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, DISPATCH_ERROR,
                    "章节工作流暂时不可用：" + exception.getMessage());
        }
        return response(task);
    }

    public ChapterTaskResponse response(AiTask task) {
        return new ChapterTaskResponse(task.getId(), task.getChapterId(), task.getStatus());
    }
    public AiTask find(Long userId, String key) {
        return tasks.selectOne(Wrappers.<AiTask>lambdaQuery()
                .eq(AiTask::getUserId, userId).eq(AiTask::getIdempotencyKey, key));
    }
    public JsonNode payload(AiTask task) {
        JsonNode request = support.read(task.getRequestPayload());
        return request == null ? support.mapper().createObjectNode() : request.path("payload");
    }
    @Transactional public void markFailed(AiTask task, String message) {
        task.setStatus(AiTaskStatus.FAILED); task.setErrorCode(DISPATCH_ERROR);
        task.setErrorMessage(message == null ? null : message.substring(0, Math.min(1000, message.length())));
        task.setUpdatedTime(LocalDateTime.now()); tasks.updateById(task);
        if (credits.hasLog(freezeKey(task))) {
            credits.release(task.getUserId(), task.getId(), freezeKey(task), configuredCreditCost(task.getTaskType()),
                    "章节 AI 任务派发失败，释放预冻结额度");
        }
    }

    public long configuredCreditCost(String taskType) {
        if (!ChapterTaskType.isChapterTask(taskType)) {
            throw new IllegalArgumentException("不支持的章节任务类型: " + taskType);
        }
        return pricing.credits(taskType);
    }

    /** Legacy defaults kept for callers outside Spring; runtime billing uses the pricing table. */
    public static long creditCost(String taskType) {
        return switch (taskType) {
            case ChapterTaskType.PLAN -> 3L;
            case ChapterTaskType.GENERATE -> 12L;
            case ChapterTaskType.REWRITE -> 6L;
            case ChapterTaskType.FINALIZE -> 5L;
            default -> 5L;
        };
    }

    public static String freezeKey(AiTask task) {
        return "chapter:freeze:" + task.getIdempotencyKey();
    }

    public static String settleKey(AiTask task) {
        return "chapter:settle:" + task.getId();
    }
    private String requestPayload(String action, JsonNode payload) {
        ObjectNode root = support.mapper().createObjectNode(); root.put("action", action); root.set("payload", payload);
        return support.write(root);
    }
    private String retryKey(String baseKey, int attemptNo) {
        return "chapter-retry:" + support.sha256(baseKey).substring(0, 32) + ":" + attemptNo;
    }
}
