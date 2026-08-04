package com.storyforge.cost;

public record AiEntitlementResponse(
        String planCode,
        Long dailyLimit,
        Long monthlyLimit,
        Integer maxConcurrentTasks,
        String status
) { }
