package com.storyforge.chapter.stream;

import java.util.List;import java.util.Map;
import com.storyforge.common.config.ChapterWorkflowProperties;
import org.slf4j.Logger;import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessages;import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="app.chapter-workflow",name="redis-enabled",havingValue="true")
public class ChapterPendingEventReclaimer{
    private static final Logger log=LoggerFactory.getLogger(ChapterPendingEventReclaimer.class);
    private final StringRedisTemplate redis;private final ChapterEventStreamListener listener;private final ChapterWorkflowProperties p;
    public ChapterPendingEventReclaimer(StringRedisTemplate redis,ChapterEventStreamListener listener,ChapterWorkflowProperties p){this.redis=redis;this.listener=listener;this.p=p;}
    @Scheduled(initialDelayString="${app.chapter-workflow.reclaim-interval-ms:10000}",fixedDelayString="${app.chapter-workflow.reclaim-interval-ms:10000}")
    public void reclaim(){try{var ops=redis.opsForStream();PendingMessages pending=ops.pending(p.eventStream(),p.eventGroup(),Range.unbounded(),p.reclaimBatchSize());
        RecordId[] ids=pending.stream().filter(m->m.getElapsedTimeSinceLastDelivery().compareTo(p.reclaimIdle())>=0).map(m->m.getId()).toArray(RecordId[]::new);
        if(ids.length==0)return;List<MapRecord<String,Object,Object>> claimed=ops.claim(p.eventStream(),p.eventGroup(),p.eventConsumer(),p.reclaimIdle(),ids);
        for(var record:claimed)listener.onMessage(record.mapEntries(e->Map.entry(String.valueOf(e.getKey()),String.valueOf(e.getValue()))));
        }catch(RuntimeException e){log.error("Failed to reclaim pending chapter events",e);}}
}
