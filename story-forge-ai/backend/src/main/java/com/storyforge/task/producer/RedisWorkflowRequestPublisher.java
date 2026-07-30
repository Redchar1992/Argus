package com.storyforge.task.producer;

import java.util.Map;

import com.storyforge.common.config.WorkflowProperties;

import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisWorkflowRequestPublisher implements WorkflowRequestPublisher {

    private final StringRedisTemplate redisTemplate;
    private final WorkflowProperties properties;

    public RedisWorkflowRequestPublisher(
            StringRedisTemplate redisTemplate,
            WorkflowProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public String publish(Map<String, String> fields) {
        try {
            RecordId recordId = redisTemplate.opsForStream().add(
                    StreamRecords.string(fields).withStreamKey(properties.requestStream())
            );
            if (recordId == null) {
                throw new WorkflowDispatchException("Redis 未返回消息 ID");
            }
            return recordId.getValue();
        } catch (WorkflowDispatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WorkflowDispatchException("无法发布工作流请求到 Redis Stream", exception);
        }
    }
}
