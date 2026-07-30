package com.storyforge.story;

import java.util.List;

import com.storyforge.common.security.AuthenticatedUser;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/story")
public class StoryController {

    private final StoryService storyService;

    public StoryController(StoryService storyService) {
        this.storyService = storyService;
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    public StoryResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateStoryRequest request
    ) {
        return storyService.create(user.userId(), request);
    }

    @GetMapping("/list")
    public List<StoryResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return storyService.list(user.userId());
    }

    @GetMapping("/{id}")
    public StoryResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id
    ) {
        return storyService.get(user.userId(), id);
    }

    @PutMapping("/{id}/selection")
    public StoryResponse selectTopic(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long id,
            @Valid @RequestBody SelectTopicRequest request
    ) {
        return storyService.selectTopic(user.userId(), id, request);
    }
}
