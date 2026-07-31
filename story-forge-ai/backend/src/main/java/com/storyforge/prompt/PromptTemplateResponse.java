package com.storyforge.prompt;

import java.time.LocalDateTime;

public record PromptTemplateResponse(
        Long id,
        String promptKey,
        String promptType,
        Integer versionNo,
        String systemPrompt,
        String userTemplate,
        String outputSchema,
        String modelProfile,
        String status,
        String changeSummary,
        LocalDateTime createdTime,
        LocalDateTime publishedTime
) { }
