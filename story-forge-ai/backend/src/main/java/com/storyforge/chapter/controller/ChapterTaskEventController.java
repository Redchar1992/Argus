package com.storyforge.chapter.controller;

import java.util.List;
import com.storyforge.chapter.service.ChapterSseService;
import com.storyforge.chapter.vo.TaskEventResponse;
import com.storyforge.common.security.AuthenticatedUser;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChapterTaskEventController {
    private final ChapterSseService service;
    public ChapterTaskEventController(ChapterSseService service){this.service=service;}
    @GetMapping(value="/api/ai-tasks/{taskId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long taskId,
            @RequestHeader(value="Last-Event-ID",required=false) String lastEventId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering","no")
                .body(service.subscribe(user.userId(),taskId,lastEventId));
    }
    @GetMapping(value="/api/ai-tasks/{taskId}/events/history",produces=MediaType.APPLICATION_JSON_VALUE)
    public List<TaskEventResponse> history(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable Long taskId,
            @RequestParam(required=false) String after){return service.list(user.userId(),taskId,after);}
}
