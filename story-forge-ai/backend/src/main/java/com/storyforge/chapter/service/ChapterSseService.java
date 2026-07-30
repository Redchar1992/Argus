package com.storyforge.chapter.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.chapter.ChapterTaskType;
import com.storyforge.chapter.entity.AiTaskEvent;
import com.storyforge.chapter.mapper.AiTaskEventMapper;
import com.storyforge.chapter.stream.ChapterSseHub;
import com.storyforge.chapter.vo.TaskEventResponse;
import com.storyforge.common.exception.ApiException;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ChapterSseService {
    private final AiTaskService tasks;private final AiTaskEventMapper events;private final ChapterEventService eventService;
    private final ChapterSseHub hub;private final ChapterSupport support;
    public ChapterSseService(AiTaskService tasks,AiTaskEventMapper events,ChapterEventService eventService,
            ChapterSseHub hub,ChapterSupport support){this.tasks=tasks;this.events=events;this.eventService=eventService;this.hub=hub;this.support=support;}
    public SseEmitter subscribe(Long userId,Long taskId,String lastEventId){
        AiTask task=tasks.requireOwned(userId,taskId);
        if(!ChapterTaskType.isChapterTask(task.getTaskType()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"NOT_CHAPTER_TASK","该任务不提供章节事件流");
        return hub.subscribe(taskId,()->replay(task,lastEventId));
    }
    public List<TaskEventResponse> list(Long userId,Long taskId,String lastEventId){
        AiTask task=tasks.requireOwned(userId,taskId);
        if(!ChapterTaskType.isChapterTask(task.getTaskType()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"NOT_CHAPTER_TASK","该任务不提供章节事件流");
        return replay(task,lastEventId);
    }
    private List<TaskEventResponse> replay(AiTask task,String cursor){
        Long afterId=null;boolean missing=false;
        if(StringUtils.hasText(cursor)){
            AiTaskEvent found=events.selectOne(Wrappers.<AiTaskEvent>lambdaQuery()
                    .eq(AiTaskEvent::getTaskId,task.getId()).eq(AiTaskEvent::getRedisEventId,cursor.trim()));
            if(found==null&&cursor.trim().matches("\\d+"))found=events.selectByTaskAndSequence(task.getId(),Long.parseLong(cursor.trim()));
            if(found!=null)afterId=found.getId();else missing=true;
        }
        var query=Wrappers.<AiTaskEvent>lambdaQuery().eq(AiTaskEvent::getTaskId,task.getId());
        if(afterId!=null)query.gt(AiTaskEvent::getId,afterId);
        query.orderByAsc(AiTaskEvent::getSequenceNo);
        List<TaskEventResponse> result=new ArrayList<>();
        List<AiTaskEvent> stored=events.selectList(query);
        if(missing&&!stored.isEmpty()){
            ObjectNode data=support.mapper().createObjectNode();data.put("reason","EVENT_HISTORY_TRUNCATED");
            data.put("firstAvailableEventId",stored.get(0).getRedisEventId());
            result.add(new TaskEventResponse("reset-"+stored.get(0).getRedisEventId(),task.getId(),task.getStoryId(),
                    task.getChapterId(),null,"STREAM_RESET",0L,task.getStatus(),task.getCurrentNode(),task.getProgress(),
                    data,null,null,LocalDateTime.now()));
        }
        stored.stream().map(eventService::response).forEach(result::add);return result;
    }
}
