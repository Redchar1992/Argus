package com.storyforge.prompt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PromptTemplateRequest(
        @NotBlank @Size(max = 100) String promptKey,
        @NotBlank @Size(max = 50) String promptType,
        @NotBlank String systemPrompt,
        @NotBlank String userTemplate,
        String outputSchema,
        @Size(max = 64) String modelProfile,
        @Size(max = 1000) String changeSummary
) { }
