package com.storyforge.common.config;

import java.nio.charset.StandardCharsets;

import com.storyforge.task.consumer.WorkflowEventStreamListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

@Configuration
@EnableScheduling
public class WorkflowStreamConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStreamConfig.class);

    // Spring Data Redis containers deliberately report isAutoStartup=false, so
    // the listener must be started explicitly when the bean is initialized.
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnProperty(prefix = "app.workflow", name = "redis-enabled", havingValue = "true")
    StreamMessageListenerContainer<String, MapRecord<String, String, String>> workflowEventContainer(
            RedisConnectionFactory connectionFactory,
            WorkflowEventStreamListener listener,
            WorkflowProperties properties
    ) {
        ensureConsumerGroup(connectionFactory, properties);
        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .serializer(RedisSerializer.string())
                .pollTimeout(properties.pollTimeout())
                .batchSize(properties.batchSize())
                .errorHandler(error -> log.error("Workflow event stream polling failed", error))
                .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        container.receive(
                Consumer.from(properties.eventGroup(), properties.eventConsumer()),
                StreamOffset.create(properties.eventStream(), ReadOffset.lastConsumed()),
                listener
        );
        return container;
    }

    private void ensureConsumerGroup(
            RedisConnectionFactory connectionFactory,
            WorkflowProperties properties
    ) {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            connection.streamCommands().xGroupCreate(
                    properties.eventStream().getBytes(StandardCharsets.UTF_8),
                    properties.eventGroup(),
                    ReadOffset.from("0-0"),
                    true
            );
        } catch (DataAccessException exception) {
            if (!containsBusyGroup(exception)) {
                throw exception;
            }
        }
    }

    private boolean containsBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
