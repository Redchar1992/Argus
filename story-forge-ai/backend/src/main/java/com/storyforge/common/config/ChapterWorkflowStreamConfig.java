package com.storyforge.common.config;

import java.nio.charset.StandardCharsets;
import com.storyforge.chapter.stream.ChapterEventStreamListener;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;

@Configuration
public class ChapterWorkflowStreamConfig{
    private static final Logger log=LoggerFactory.getLogger(ChapterWorkflowStreamConfig.class);
    @Bean(initMethod="start",destroyMethod="stop")
    @ConditionalOnProperty(prefix="app.chapter-workflow",name="redis-enabled",havingValue="true")
    StreamMessageListenerContainer<String,MapRecord<String,String,String>> chapterEventContainer(
            RedisConnectionFactory factory,ChapterEventStreamListener listener,ChapterWorkflowProperties p){
        ensureGroup(factory,p);var options=StreamMessageListenerContainer.StreamMessageListenerContainerOptions.builder()
                .serializer(RedisSerializer.string()).pollTimeout(p.pollTimeout()).batchSize(p.batchSize())
                .errorHandler(error->log.error("Chapter event stream polling failed",error)).build();
        var container=StreamMessageListenerContainer.<String,MapRecord<String,String,String>>create(factory,options);
        container.receive(Consumer.from(p.eventGroup(),p.eventConsumer()),
                StreamOffset.create(p.eventStream(),ReadOffset.lastConsumed()),listener);return container;
    }
    private void ensureGroup(RedisConnectionFactory factory,ChapterWorkflowProperties p){
        try(RedisConnection c=factory.getConnection()){c.streamCommands().xGroupCreate(p.eventStream().getBytes(StandardCharsets.UTF_8),
                p.eventGroup(),ReadOffset.from("0-0"),true);}catch(DataAccessException e){if(!busy(e))throw e;}}
    private boolean busy(Throwable e){for(Throwable c=e;c!=null;c=c.getCause())if(c.getMessage()!=null&&c.getMessage().contains("BUSYGROUP"))return true;return false;}
}
