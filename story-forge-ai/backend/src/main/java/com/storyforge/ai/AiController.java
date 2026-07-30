package com.storyforge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.storyforge.common.security.AuthenticatedUser;
import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiOrchestrationService orchestrationService;

    public AiController(AiOrchestrationService orchestrationService) {
        this.orchestrationService = orchestrationService;
    }

    @PostMapping("/topic/generate")
    public JsonNode generateTopics(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody GenerateTopicRequest request
    ) {
        return orchestrationService.generate(user.userId(), request);
    }
}
