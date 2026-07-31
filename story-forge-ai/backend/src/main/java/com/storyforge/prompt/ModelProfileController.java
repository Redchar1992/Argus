package com.storyforge.prompt;

import java.util.List;

import com.storyforge.common.security.AuthenticatedUser;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-profiles")
public class ModelProfileController {
    private final ModelProfileService service;

    public ModelProfileController(ModelProfileService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModelProfileResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody ModelProfileRequest request) {
        return service.create(user.userId(), request);
    }

    @GetMapping
    public List<ModelProfileResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.userId());
    }
}
