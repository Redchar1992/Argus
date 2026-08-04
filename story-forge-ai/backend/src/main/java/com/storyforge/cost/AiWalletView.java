package com.storyforge.cost;

import java.time.LocalDateTime;

/** User-facing wallet view combining credits and platform quota. */
public record AiWalletView(
        Long userId,
        Long availableCredits,
        Long frozenCredits,
        Long consumedCredits,
        LocalDateTime updatedTime,
        String planCode,
        Long dailyLimit,
        Long monthlyLimit,
        Long dailyRemaining,
        Long monthlyRemaining,
        Integer maxConcurrentTasks
) { }
