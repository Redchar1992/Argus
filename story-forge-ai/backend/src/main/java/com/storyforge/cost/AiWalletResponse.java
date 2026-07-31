package com.storyforge.cost;

import java.time.LocalDateTime;

public record AiWalletResponse(
        Long userId,
        Long availableCredits,
        Long frozenCredits,
        Long consumedCredits,
        LocalDateTime updatedTime
) { }
