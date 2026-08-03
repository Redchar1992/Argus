package com.storyforge;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import com.storyforge.chapter.service.ChapterEventService;
import com.storyforge.chapter.stream.ChapterEventStreamListener;
import com.storyforge.chapter.stream.ChapterPendingEventReclaimer;
import com.storyforge.chapter.stream.ChapterSseHub;
import com.storyforge.common.config.ChapterWorkflowProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class ChapterEventStreamListenerTest {
    ChapterEventService service;ChapterSseHub hub;StringRedisTemplate redis;
    StreamOperations<String,Object,Object> operations;ChapterWorkflowProperties properties;ChapterEventStreamListener listener;
    @SuppressWarnings("unchecked") @BeforeEach void setUp(){
        service=mock(ChapterEventService.class);hub=mock(ChapterSseHub.class);redis=mock(StringRedisTemplate.class);
        operations=mock(StreamOperations.class);when(redis.opsForStream()).thenReturn(operations);
        properties=new ChapterWorkflowProperties(true,"story:chapter:commands","story:chapter:events","backend","consumer",
                Duration.ofSeconds(1),10,Duration.ofSeconds(60),10000,20,100000,10000,1800000);
        listener=new ChapterEventStreamListener(service,hub,redis,properties);
    }
    @Test void acknowledgesOnlyAfterTransactionAndThenPublishes(){
        var message=message("7000-0");var result=new ChapterEventService.ProcessedEvent(true,null);
        when(service.process("7000-0",message.getValue())).thenReturn(result);listener.onMessage(message);
        InOrder order=inOrder(service,hub,redis,operations);order.verify(service).process("7000-0",message.getValue());
        order.verify(hub).publish(null);order.verify(redis).opsForStream();
        order.verify(operations).acknowledge("story:chapter:events","backend",message.getId());
        order.verify(operations).delete("story:chapter:events",message.getId());
    }
    @Test void persistenceFailureLeavesDeliveryPending(){var message=message("7001-0");
        when(service.process("7001-0",message.getValue())).thenThrow(new IllegalStateException("db down"));listener.onMessage(message);
        verify(service).process("7001-0",message.getValue());verify(redis,never()).opsForStream();verifyNoInteractions(operations);}
    @Test void invalidEventIsPersistedAsFailureDeadLetteredAndAcknowledged(){var message=message("7001-1");
        var invalid=new IllegalArgumentException("memoryUpdate.characterStateChanges[0].newValue 不能为空");
        var rejected=new ChapterEventService.ProcessedEvent(true,null);
        when(service.process("7001-1",message.getValue())).thenThrow(invalid);
        when(service.rejectInvalidEvent("7001-1",message.getValue(),invalid)).thenReturn(rejected);
        listener.onMessage(message);
        verify(service).rejectInvalidEvent("7001-1",message.getValue(),invalid);
        verify(hub).publish(null);
        verify(operations).add(org.mockito.ArgumentMatchers.eq("story:chapter:events:dead-letter"),
                org.mockito.ArgumentMatchers.<Map<String,String>>any());
        verify(operations).acknowledge("story:chapter:events","backend",message.getId());
    }
    @Test void reclaimerClaimsAndRetriesPendingDelivery(){var message=message("7002-0");
        when(service.process("7002-0",message.getValue())).thenThrow(new IllegalStateException("once"))
                .thenReturn(new ChapterEventService.ProcessedEvent(false,null));listener.onMessage(message);
        PendingMessage pending=new PendingMessage(message.getId(),Consumer.from("backend","dead"),Duration.ofMinutes(2),1);
        when(operations.pending(org.mockito.ArgumentMatchers.eq("story:chapter:events"),org.mockito.ArgumentMatchers.eq("backend"),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(20L)))
                .thenReturn(new PendingMessages("backend",List.of(pending)));
        MapRecord<String,Object,Object> claimed=MapRecord.create(
                        "story:chapter:events",Map.<Object,Object>of("taskId","1"))
                .withId(message.getId());
        when(operations.claim("story:chapter:events","backend","consumer",Duration.ofSeconds(60),message.getId()))
                .thenReturn(List.of(claimed));
        new ChapterPendingEventReclaimer(redis,listener,properties).reclaim();
        verify(service,times(2)).process("7002-0",message.getValue());
        verify(operations).acknowledge("story:chapter:events","backend",message.getId());
    }
    private MapRecord<String,String,String> message(String id){
        var message=MapRecord.create("story:chapter:events",Map.of("taskId","1")).withId(RecordId.of(id));
        when(operations.acknowledge("story:chapter:events","backend",message.getId())).thenReturn(1L);
        return message;
    }
}
