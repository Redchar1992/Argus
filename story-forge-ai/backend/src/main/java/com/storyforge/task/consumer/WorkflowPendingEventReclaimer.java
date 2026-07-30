package com.storyforge.task.consumer;

import java.util.List;
import java.util.Map;

import com.storyforge.common.config.WorkflowProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.workflow", name = "redis-enabled", havingValue = "true")
public class WorkflowPendingEventReclaimer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPendingEventReclaimer.class);

    private final StringRedisTemplate redisTemplate;
    private final WorkflowEventStreamListener listener;
    private final WorkflowProperties properties;

    public WorkflowPendingEventReclaimer(
            StringRedisTemplate redisTemplate,
            WorkflowEventStreamListener listener,
            WorkflowProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.listener = listener;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${app.workflow.reclaim-interval-ms:10000}",
            fixedDelayString = "${app.workflow.reclaim-interval-ms:10000}"
    )
    public void reclaimPendingEvents() {
        try {
            var operations = redisTemplate.opsForStream();
            PendingMessages pending = operations.pending(
                    properties.eventStream(),
                    properties.eventGroup(),
                    Range.unbounded(),
                    properties.reclaimBatchSize()
            );
            RecordId[] reclaimable = pending.stream()
                    .filter(message -> message.getElapsedTimeSinceLastDelivery()
                            .compareTo(properties.reclaimIdle()) >= 0)
                    .map(message -> message.getId())
                    .toArray(RecordId[]::new);
            if (reclaimable.length == 0) {
                return;
            }

            List<MapRecord<String, Object, Object>> claimed = operations.claim(
                    properties.eventStream(),
                    properties.eventGroup(),
                    properties.eventConsumer(),
                    properties.reclaimIdle(),
                    reclaimable
            );
            for (MapRecord<String, Object, Object> record : claimed) {
                listener.onMessage(record.mapEntries(entry -> Map.entry(
                        String.valueOf(entry.getKey()),
                        String.valueOf(entry.getValue())
                )));
            }
        } catch (RuntimeException exception) {
            log.error("Failed to reclaim pending workflow events", exception);
        }
    }
}
