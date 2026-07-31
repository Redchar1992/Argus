package com.storyforge.cost;

public record AiUsageSummary(
        long calls,
        long successfulCalls,
        long inputTokens,
        long outputTokens,
        double estimatedCost
) { }
