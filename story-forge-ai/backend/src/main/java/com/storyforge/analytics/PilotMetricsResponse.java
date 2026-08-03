package com.storyforge.analytics;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PilotMetricsResponse(
        LocalDateTime generatedAt,
        LocalDateTime from,
        int windowDays,
        long registeredUsers,
        long activeUsers,
        List<FunnelStep> funnel,
        TaskHealth taskHealth,
        ModelUsage modelUsage
) {
    public record FunnelStep(
            String eventName,
            long events,
            long users,
            long stories
    ) {
    }

    public record TaskHealth(
            long successful,
            long failed,
            long inFlight,
            BigDecimal successRate
    ) {
    }

    public record ModelUsage(
            long calls,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCost
    ) {
    }
}
