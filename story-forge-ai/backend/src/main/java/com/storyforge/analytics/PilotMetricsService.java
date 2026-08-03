package com.storyforge.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PilotMetricsService {
    private final JdbcTemplate jdbc;

    public PilotMetricsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PilotMetricsResponse snapshot(int days) {
        int boundedDays = Math.max(1, Math.min(days, 90));
        LocalDateTime generatedAt = LocalDateTime.now();
        LocalDateTime from = generatedAt.minusDays(boundedDays);

        Map<String, PilotMetricsResponse.FunnelStep> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT event_name, COUNT(*) AS event_count,
                       COUNT(DISTINCT user_id) AS user_count,
                       COUNT(DISTINCT story_id) AS story_count
                FROM product_event pe
                JOIN sys_user u ON u.id = pe.user_id
                WHERE u.created_time >= ?
                  AND u.created_time <= ?
                  AND pe.occurred_time <= ?
                GROUP BY pe.event_name
                """, (result, row) -> new PilotMetricsResponse.FunnelStep(
                        result.getString("event_name"),
                        result.getLong("event_count"),
                        result.getLong("user_count"),
                        result.getLong("story_count")
                ), from, generatedAt, generatedAt)
                .forEach(step -> rows.put(step.eventName(), step));
        List<PilotMetricsResponse.FunnelStep> funnel = ProductEventNames.FUNNEL.stream()
                .map(name -> rows.getOrDefault(
                        name,
                        new PilotMetricsResponse.FunnelStep(name, 0, 0, 0)
                ))
                .toList();

        long registeredUsers = value("""
                SELECT COUNT(*) FROM sys_user WHERE created_time >= ?
                """, from);
        long activeUsers = value("""
                SELECT COUNT(DISTINCT user_id) FROM product_event WHERE occurred_time >= ?
                """, from);
        Map<String, Long> taskCounts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT status, COUNT(*) AS task_count
                FROM ai_task
                WHERE created_time >= ?
                GROUP BY status
                """, (result, row) -> Map.entry(
                result.getString("status"), result.getLong("task_count")
        ), from).forEach(entry -> taskCounts.put(entry.getKey(), entry.getValue()));
        long successful = taskCounts.getOrDefault("SUCCESS", 0L);
        long failed = taskCounts.getOrDefault("FAILED", 0L);
        long inFlight = taskCounts.getOrDefault("WAITING", 0L)
                + taskCounts.getOrDefault("RUNNING", 0L)
                + taskCounts.getOrDefault("REVIEW_REQUIRED", 0L);
        long terminal = successful + failed;
        BigDecimal successRate = terminal == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(successful)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(terminal), 2, RoundingMode.HALF_UP);

        PilotMetricsResponse.ModelUsage modelUsage = jdbc.queryForObject("""
                SELECT COUNT(*) AS calls,
                       COALESCE(SUM(input_tokens), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS output_tokens,
                       COALESCE(SUM(estimated_cost), 0) AS estimated_cost
                FROM ai_model_usage
                WHERE created_time >= ?
                """, (result, row) -> new PilotMetricsResponse.ModelUsage(
                result.getLong("calls"),
                result.getLong("input_tokens"),
                result.getLong("output_tokens"),
                result.getBigDecimal("estimated_cost") == null
                        ? BigDecimal.ZERO
                        : result.getBigDecimal("estimated_cost")
        ), from);

        return new PilotMetricsResponse(
                generatedAt,
                from,
                boundedDays,
                registeredUsers,
                activeUsers,
                funnel,
                new PilotMetricsResponse.TaskHealth(
                        successful,
                        failed,
                        inFlight,
                        successRate
                ),
                modelUsage
        );
    }

    private long value(String sql, LocalDateTime from) {
        Long result = jdbc.queryForObject(sql, Long.class, from);
        return result == null ? 0 : result;
    }
}
