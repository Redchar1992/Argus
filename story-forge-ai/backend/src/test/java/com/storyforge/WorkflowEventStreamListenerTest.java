package com.storyforge;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.List;

import com.storyforge.common.config.WorkflowProperties;
import com.storyforge.task.consumer.WorkflowEventService;
import com.storyforge.task.consumer.WorkflowEventStreamListener;
import com.storyforge.task.consumer.WorkflowPendingEventReclaimer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class WorkflowEventStreamListenerTest {

    private WorkflowEventService eventService;
    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, Object, Object> streamOperations;
    private WorkflowEventStreamListener listener;
    private WorkflowProperties properties;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        eventService = mock(WorkflowEventService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        streamOperations = mock(StreamOperations.class);
        when(redisTemplate.opsForStream()).thenReturn(streamOperations);
        properties = new WorkflowProperties(
                true,
                "story:workflow:requests",
                "story:workflow:events",
                "storyforge-backend",
                "backend-test",
                Duration.ofSeconds(1),
                10,
                Duration.ofSeconds(60),
                10_000L,
                20
        );
        listener = new WorkflowEventStreamListener(eventService, redisTemplate, properties);
    }

    @Test
    void acknowledgesOnlyAfterPersistenceReturns() {
        MapRecord<String, String, String> message = message("3000-0");
        when(streamOperations.acknowledge(
                "story:workflow:events",
                "storyforge-backend",
                message.getId()
        )).thenReturn(1L);

        listener.onMessage(message);

        InOrder order = inOrder(eventService, redisTemplate, streamOperations);
        order.verify(eventService).processEvent("3000-0", message.getValue());
        order.verify(redisTemplate).opsForStream();
        order.verify(streamOperations).acknowledge(
                "story:workflow:events",
                "storyforge-backend",
                message.getId()
        );
        order.verify(redisTemplate).opsForStream();
        order.verify(streamOperations).delete(
                "story:workflow:events",
                message.getId()
        );
    }

    @Test
    void leavesMessagePendingWhenPersistenceFails() {
        MapRecord<String, String, String> message = message("3001-0");
        when(eventService.processEvent("3001-0", message.getValue()))
                .thenThrow(new IllegalStateException("database unavailable"));

        listener.onMessage(message);

        verify(eventService).processEvent("3001-0", message.getValue());
        verify(redisTemplate, never()).opsForStream();
        verifyNoInteractions(streamOperations);
    }

    @Test
    void pendingMessageCanBeClaimedAndProcessedAgainAfterFailure() {
        MapRecord<String, String, String> firstDelivery = message("3002-0");
        when(eventService.processEvent("3002-0", firstDelivery.getValue()))
                .thenThrow(new IllegalStateException("temporary database failure"))
                .thenReturn(true);

        listener.onMessage(firstDelivery);

        PendingMessage pendingMessage = new PendingMessage(
                firstDelivery.getId(),
                Consumer.from("storyforge-backend", "dead-consumer"),
                Duration.ofMinutes(2),
                1
        );
        when(streamOperations.pending(
                org.mockito.ArgumentMatchers.eq("story:workflow:events"),
                org.mockito.ArgumentMatchers.eq("storyforge-backend"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(20L)
        )).thenReturn(new PendingMessages("storyforge-backend", List.of(pendingMessage)));

        Map<Object, Object> claimedFields = Map.of("taskId", "1", "status", "RUNNING");
        MapRecord<String, Object, Object> claimed = MapRecord
                .create("story:workflow:events", claimedFields)
                .withId(firstDelivery.getId());
        when(streamOperations.claim(
                "story:workflow:events",
                "storyforge-backend",
                "backend-test",
                Duration.ofSeconds(60),
                firstDelivery.getId()
        )).thenReturn(List.of(claimed));

        new WorkflowPendingEventReclaimer(redisTemplate, listener, properties)
                .reclaimPendingEvents();

        verify(eventService, times(2)).processEvent("3002-0", firstDelivery.getValue());
        verify(streamOperations).acknowledge(
                "story:workflow:events",
                "storyforge-backend",
                firstDelivery.getId()
        );
    }

    private MapRecord<String, String, String> message(String id) {
        return MapRecord.create(
                "story:workflow:events",
                Map.of("taskId", "1", "status", "RUNNING")
        ).withId(RecordId.of(id));
    }
}
