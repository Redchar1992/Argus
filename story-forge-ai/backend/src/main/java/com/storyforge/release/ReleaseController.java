package com.storyforge.release;

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
@RequestMapping("/api")
public class ReleaseController {
    private final ReleaseService service;

    public ReleaseController(ReleaseService service) {
        this.service = service;
    }

    @PostMapping("/stories/{storyId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable Long storyId,
                                  @Valid @RequestBody(required = false) CreateReleaseRequest request) {
        return service.create(user.userId(), storyId, request == null ? null : request.reportId());
    }

    @GetMapping("/stories/{storyId}/releases")
    public List<ReleaseResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable Long storyId) {
        return service.list(user.userId(), storyId);
    }

    @GetMapping("/releases/{releaseId}")
    public ReleaseResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable Long releaseId) {
        return service.get(user.userId(), releaseId);
    }
}
