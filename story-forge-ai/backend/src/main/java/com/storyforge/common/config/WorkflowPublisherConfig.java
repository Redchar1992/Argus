package com.storyforge.common.config;

import com.storyforge.task.producer.RedisWorkflowRequestPublisher;
import com.storyforge.task.producer.UnavailableWorkflowRequestPublisher;
import com.storyforge.task.producer.WorkflowRequestPublisher;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class WorkflowPublisherConfig {

    @Bean
    WorkflowRequestPublisher workflowRequestPublisher(
            StringRedisTemplate redisTemplate,
            WorkflowProperties properties
    ) {
        return properties.redisEnabled()
                ? new RedisWorkflowRequestPublisher(redisTemplate, properties)
                : new UnavailableWorkflowRequestPublisher();
    }
}
