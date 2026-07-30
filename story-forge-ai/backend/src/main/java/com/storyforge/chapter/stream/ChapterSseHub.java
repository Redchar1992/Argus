package com.storyforge.chapter.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import com.storyforge.chapter.vo.TaskEventResponse;
import com.storyforge.common.config.ChapterWorkflowProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class ChapterSseHub {
    private static final int LOCK_STRIPE_COUNT=256;
    private final ChapterWorkflowProperties properties;
    private final Object[] locks=new Object[LOCK_STRIPE_COUNT];
    private final Map<Long,List<SseEmitter>> subscribers=new ConcurrentHashMap<>();
    public ChapterSseHub(ChapterWorkflowProperties properties){
        this.properties=properties;
        for(int index=0;index<locks.length;index++)locks[index]=new Object();
    }

    public SseEmitter subscribe(Long taskId,Supplier<List<TaskEventResponse>> replay){
        Object lock=lockFor(taskId);
        synchronized(lock){
            SseEmitter emitter=new SseEmitter(properties.sseTimeoutMs());
            List<TaskEventResponse> history=replay.get();
            try{
                for(TaskEventResponse event:history)send(emitter,event);
                if(!history.isEmpty()&&terminal(history.get(history.size()-1))){emitter.complete();return emitter;}
                subscribers.computeIfAbsent(taskId,key->new ArrayList<>()).add(emitter);
                Runnable remove=()->remove(taskId,emitter);
                emitter.onCompletion(remove);emitter.onTimeout(remove);emitter.onError(error->remove.run());
                return emitter;
            }catch(IOException exception){emitter.completeWithError(exception);return emitter;}
        }
    }
    public void publish(TaskEventResponse event){
        if(event==null)return;Object lock=lockFor(event.taskId());
        synchronized(lock){
            List<SseEmitter> list=subscribers.get(event.taskId());if(list==null)return;
            List<SseEmitter> failed=new ArrayList<>();
            boolean terminal=terminal(event);
            for(SseEmitter emitter:list)try{send(emitter,event);if(terminal)emitter.complete();}
            catch(IOException|IllegalStateException exception){emitter.completeWithError(exception);failed.add(emitter);}
            if(terminal)subscribers.remove(event.taskId());else list.removeAll(failed);
        }
    }
    private void send(SseEmitter emitter,TaskEventResponse event)throws IOException{
        emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
    }
    private void remove(Long taskId,SseEmitter emitter){Object lock=lockFor(taskId);synchronized(lock){
        List<SseEmitter> list=subscribers.get(taskId);if(list!=null){list.remove(emitter);if(list.isEmpty())subscribers.remove(taskId);}}}
    private Object lockFor(Long taskId){
        int hash=Long.hashCode(taskId);hash^=hash>>>16;
        return locks[hash&(LOCK_STRIPE_COUNT-1)];
    }
    int lockStripeCount(){return locks.length;}
    boolean terminal(TaskEventResponse event){
        if(event==null)return false;
        return switch(event.type()){
            case "CHAPTER_PLAN_READY","REWRITE_PROPOSAL_READY","FINAL_READY" -> "SUCCESS".equals(event.status());
            case "HUMAN_REVIEW_REQUIRED" -> "REVIEW_REQUIRED".equals(event.status());
            case "TASK_FAILED" -> "FAILED".equals(event.status());
            default -> false;
        };
    }
}
