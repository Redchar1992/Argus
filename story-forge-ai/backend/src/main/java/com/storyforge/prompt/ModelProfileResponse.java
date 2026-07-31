package com.storyforge.prompt;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ModelProfileResponse(
        Long id,
        String profileKey,
        String provider,
        String modelName,
        BigDecimal temperature,
        Integer maxTokens,
        String secretReference,
        String status,
        LocalDateTime updatedTime
) { }
