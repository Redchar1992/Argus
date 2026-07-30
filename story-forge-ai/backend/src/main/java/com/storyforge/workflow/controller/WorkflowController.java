package com.storyforge.workflow.controller;

import com.storyforge.common.security.AuthenticatedUser;
import com.storyforge.workflow.dto.ReviewDecisionRequest;
import com.storyforge.workflow.dto.StartWorkflowRequest;
import com.storyforge.workflow.service.WorkflowService;
import com.storyforge.workflow.vo.WorkflowReviewResponse;
import com.storyforge.workflow.vo.WorkflowTaskCreatedResponse;
import com.storyforge.workflow.vo.WorkflowTaskStatusResponse;
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
public class WorkflowController {

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/stories/{storyId}/workflow")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowTaskCreatedResponse start(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long storyId,
            @Valid @RequestBody StartWorkflowRequest request
    ) {
        return workflowService.start(user.userId(), storyId, request);
    }

    @GetMapping("/stories/{storyId}/workflow/latest")
    public WorkflowTaskStatusResponse getLatestTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long storyId
    ) {
        return workflowService.getLatestTask(user.userId(), storyId);
    }

    @GetMapping("/ai-tasks/{taskId}")
    public WorkflowTaskStatusResponse getTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long taskId
    ) {
        return workflowService.getTask(user.userId(), taskId);
    }

    @GetMapping("/ai-tasks/{taskId}/review")
    public WorkflowReviewResponse getReview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long taskId
    ) {
        return workflowService.getReview(user.userId(), taskId);
    }

    @PostMapping("/ai-tasks/{taskId}/review")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public WorkflowTaskCreatedResponse review(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long taskId,
            @Valid @RequestBody ReviewDecisionRequest request
    ) {
        return workflowService.review(user.userId(), taskId, request);
    }
}
