package com.storyforge.chapter.stream;

import java.util.LinkedHashMap;
import java.util.Map;
import com.storyforge.chapter.service.ChapterEventService;
import com.storyforge.common.config.ChapterWorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

@Component
public class ChapterEventStreamListener implements StreamListener<String,MapRecord<String,String,String>>{
    private static final Logger log=LoggerFactory.getLogger(ChapterEventStreamListener.class);
    private final ChapterEventService service;private final ChapterSseHub hub;
    private final StringRedisTemplate redis;private final ChapterWorkflowProperties properties;
    public ChapterEventStreamListener(ChapterEventService service,ChapterSseHub hub,StringRedisTemplate redis,
            ChapterWorkflowProperties properties){this.service=service;this.hub=hub;this.redis=redis;this.properties=properties;}
    @Override public void onMessage(MapRecord<String,String,String> message){
        try{
            var result=service.process(message.getId().getValue(),message.getValue());
            complete(message,result);
        }catch(IllegalArgumentException invalid){
            try{
                var result=service.rejectInvalidEvent(message.getId().getValue(),message.getValue(),invalid);
                deadLetter(message,invalid);
                complete(message,result);
                log.warn("Rejected invalid chapter event {}: {}",message.getId().getValue(),invalid.getMessage());
            }catch(RuntimeException persistenceFailure){
                log.error("Failed to reject invalid chapter event {}; leaving it pending",message.getId().getValue(),persistenceFailure);
            }
        }catch(RuntimeException exception){log.error("Failed to persist chapter event {}; leaving it pending",message.getId().getValue(),exception);}
    }
    private void complete(MapRecord<String,String,String> message,ChapterEventService.ProcessedEvent result){
        if(result.persisted())hub.publish(result.event());
        redis.opsForStream().acknowledge(properties.eventStream(),properties.eventGroup(),message.getId());
        redis.opsForStream().trim(properties.eventStream(),properties.streamMaxLength(),true);
    }
    private void deadLetter(MapRecord<String,String,String> message,RuntimeException failure){
        Map<String,String> fields=new LinkedHashMap<>(message.getValue());
        fields.put("_sourceEventId",message.getId().getValue());
        fields.put("_errorClass",failure.getClass().getSimpleName());
        String detail=failure.getMessage()==null?"invalid chapter event":failure.getMessage();
        fields.put("_errorMessage",detail.length()<=1000?detail:detail.substring(0,1000));
        String stream=properties.eventStream()+":dead-letter";
        redis.opsForStream().add(stream,fields);
        redis.opsForStream().trim(stream,properties.streamMaxLength(),true);
    }
}
