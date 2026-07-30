package com.storyforge.common.config;

import com.storyforge.chapter.stream.ChapterCommandPublisher;
import com.storyforge.chapter.stream.RedisChapterCommandPublisher;
import com.storyforge.chapter.stream.UnavailableChapterCommandPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class ChapterWorkflowPublisherConfig {
    @Bean
    ChapterCommandPublisher chapterCommandPublisher(StringRedisTemplate redis, ChapterWorkflowProperties properties) {
        return properties.redisEnabled()
                ? new RedisChapterCommandPublisher(redis, properties)
                : new UnavailableChapterCommandPublisher();
    }
}
