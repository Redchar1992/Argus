package com.storyforge.task;

import java.util.Collection;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AiTaskService {

    private final AiTaskMapper taskMapper;

    public AiTaskService(AiTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public AiTask requireOwned(Long userId, Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AI_TASK_NOT_FOUND", "AI 任务不存在");
        }
        if (!task.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_TASK_FORBIDDEN", "无权访问该 AI 任务");
        }
        return task;
    }

    public AiTask findByIdempotencyKey(Long userId, String idempotencyKey) {
        return taskMapper.selectOne(
                Wrappers.<AiTask>lambdaQuery()
                        .eq(AiTask::getUserId, userId)
                        .eq(AiTask::getIdempotencyKey, idempotencyKey)
        );
    }

    public AiTask findLatestForStory(Long userId, Long storyId, Collection<String> taskTypes) {
        return taskMapper.selectOne(
                Wrappers.<AiTask>lambdaQuery()
                        .eq(AiTask::getUserId, userId)
                        .eq(AiTask::getStoryId, storyId)
                        .in(AiTask::getTaskType, taskTypes)
                        .orderByDesc(AiTask::getCreatedTime)
                        .orderByDesc(AiTask::getId)
                        .last("LIMIT 1")
        );
    }
}
