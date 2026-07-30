package com.storyforge.task.consumer;

import com.storyforge.common.config.WorkflowProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Component
public class WorkflowEventStreamListener
        implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventStreamListener.class);

    private final WorkflowEventService eventService;
    private final StringRedisTemplate redisTemplate;
    private final WorkflowProperties properties;

    public WorkflowEventStreamListener(
            WorkflowEventService eventService,
            StringRedisTemplate redisTemplate,
            WorkflowProperties properties
    ) {
        this.eventService = eventService;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            // processEvent is transactional. The proxy commits before it returns;
            // only then is XACK issued. A crash between commit and XACK is safe
            // because event IDs and artifact version keys are idempotent.
            eventService.processEvent(message.getId().getValue(), message.getValue());
            redisTemplate.opsForStream().acknowledge(
                    properties.eventStream(),
                    properties.eventGroup(),
                    message.getId()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Failed to persist workflow event {}; leaving it pending",
                    message.getId().getValue(),
                    exception
            );
        }
    }
}
