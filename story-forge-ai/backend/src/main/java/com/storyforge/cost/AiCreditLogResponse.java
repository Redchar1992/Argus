package com.storyforge.cost;

import java.time.LocalDateTime;

public record AiCreditLogResponse(
        Long id,
        String operationType,
        Long amount,
        Long balanceBefore,
        Long balanceAfter,
        String description,
        LocalDateTime createdTime
) { }
