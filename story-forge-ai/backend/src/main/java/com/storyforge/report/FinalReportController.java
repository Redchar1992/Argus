package com.storyforge.report;

import java.util.List;

import com.storyforge.common.security.AuthenticatedUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/final-reviews")
public class FinalReportController {
    private final FinalReportService service;

    public FinalReportController(FinalReportService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FinalReportResponse run(@AuthenticationPrincipal AuthenticatedUser user,
                                   @PathVariable Long storyId) {
        return service.run(user.userId(), storyId);
    }

    @GetMapping("/latest")
    public FinalReportResponse latest(@AuthenticationPrincipal AuthenticatedUser user,
                                      @PathVariable Long storyId) {
        return service.latest(user.userId(), storyId);
    }

    @GetMapping
    public List<FinalReportResponse> list(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable Long storyId) {
        return service.list(user.userId(), storyId);
    }
}
