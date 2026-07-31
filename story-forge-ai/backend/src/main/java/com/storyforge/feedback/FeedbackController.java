package com.storyforge.feedback;

import java.util.List;

import com.storyforge.common.security.AuthenticatedUser;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/feedback")
public class FeedbackController {
    private final FeedbackService service;

    public FeedbackController(FeedbackService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable Long storyId,
                                   @Valid @RequestBody FeedbackRequest request) {
        return service.create(user.userId(), storyId, request);
    }

    @GetMapping
    public List<FeedbackResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                       @PathVariable Long storyId) {
        return service.list(user.userId(), storyId);
    }
}
