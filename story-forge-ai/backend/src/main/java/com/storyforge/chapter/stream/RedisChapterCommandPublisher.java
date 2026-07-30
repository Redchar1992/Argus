package com.storyforge.chapter.stream;

import java.util.Map;
import com.storyforge.common.config.ChapterWorkflowProperties;
import com.storyforge.task.producer.WorkflowDispatchException;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisChapterCommandPublisher implements ChapterCommandPublisher {
    private final StringRedisTemplate redis;
    private final ChapterWorkflowProperties properties;
    public RedisChapterCommandPublisher(StringRedisTemplate redis, ChapterWorkflowProperties properties) {
        this.redis = redis; this.properties = properties;
    }
    @Override public String publish(Map<String, String> fields) {
        try {
            RecordId id = redis.opsForStream().add(
                    StreamRecords.string(fields).withStreamKey(properties.commandStream()));
            if (id == null) throw new WorkflowDispatchException("Redis 未返回章节命令 ID");
            redis.opsForStream().trim(properties.commandStream(), properties.streamMaxLength(), true);
            return id.getValue();
        } catch (WorkflowDispatchException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new WorkflowDispatchException("无法发布章节命令到 Redis Stream", exception);
        }
    }
}
