package com.storyforge.prompt;

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
@RequestMapping("/api/prompts")
public class PromptController {
    private final PromptService service;

    public PromptController(PromptService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PromptTemplateResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                         @Valid @RequestBody PromptTemplateRequest request) {
        return service.create(user.userId(), request);
    }

    @GetMapping
    public List<PromptTemplateResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId());
    }

    @GetMapping("/{id}")
    public PromptTemplateResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.get(user.userId(), id);
    }

    @PutMapping("/{id}")
    public PromptTemplateResponse update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id,
                                         @Valid @RequestBody PromptTemplateRequest request) {
        return service.update(user.userId(), id, request);
    }

    @PostMapping("/{id}/test")
    public PromptTemplateResponse test(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.markTesting(user.userId(), id);
    }

    @PostMapping("/{id}/publish")
    public PromptTemplateResponse publish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.publish(user.userId(), id);
    }

    @PostMapping("/{id}/rollback")
    public PromptTemplateResponse rollback(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long id) {
        return service.publish(user.userId(), id);
    }
}
