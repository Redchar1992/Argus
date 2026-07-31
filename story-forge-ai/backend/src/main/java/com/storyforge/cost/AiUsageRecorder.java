package com.storyforge.cost;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.task.AiTask;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Persists one accounting row for each AI task.  Workflow events are at-least-once
 * deliveries, so the recorder deliberately treats (task, agent) as an idempotent
 * boundary and never double-counts a replayed terminal event.
 */
@Service
public class AiUsageRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AiUsageRecorder(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void record(AiTask task, String agentType, String provider, String modelName,
            String promptKey, String promptVersion, long inputTokens, long outputTokens,
            long durationMs, boolean success, String errorCode) {
        if (task == null || task.getUserId() == null || agentType == null) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_model_usage WHERE task_id=? AND agent_type=?",
                Integer.class, task.getId(), agentType);
        if (count != null && count > 0) {
            return;
        }
        long input = Math.max(0, inputTokens);
        long output = Math.max(0, outputTokens);
        double estimated = (input + output) / 100000.0d;
        jdbc.update("""
                INSERT INTO ai_model_usage
                (task_id, story_id, user_id, agent_type, provider, model_name, prompt_key,
                 prompt_version, input_tokens, output_tokens, estimated_cost, actual_cost,
                 cost_status, duration_ms, success, error_code, created_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, task.getId(), task.getStoryId(), task.getUserId(), agentType,
                valueOrDefault(provider, "ai-service"), valueOrDefault(modelName, "unknown"),
                promptKey, parsePromptVersion(promptVersion), input, output,
                BigDecimal.valueOf(estimated), BigDecimal.valueOf(estimated),
                success ? "ESTIMATED" : "FAILED", Math.max(0, durationMs), success,
                errorCode);
    }

    public void recordModelCalls(AiTask task, String agentType, String rawCalls,
            boolean success, String errorCode) {
        if (rawCalls == null || rawCalls.isBlank()) {
            return;
        }
        try {
            JsonNode calls = mapper.readTree(rawCalls);
            if (calls == null || !calls.isArray() || calls.isEmpty()) {
                return;
            }
            long input = 0;
            long output = 0;
            long duration = 0;
            String model = null;
            String prompt = null;
            for (JsonNode call : calls) {
                input += Math.max(0, call.path("inputTokens").asLong(0));
                output += Math.max(0, call.path("outputTokens").asLong(0));
                duration += Math.max(0, call.path("durationMs").asLong(0));
                if (model == null || model.isBlank()) {
                    model = firstText(call, "modelName", "model");
                }
                if (prompt == null || prompt.isBlank()) {
                    prompt = firstText(call, "promptVersion", "prompt");
                }
            }
            record(task, agentType, "ai-service", model, agentType.toLowerCase(), prompt,
                    input, output, duration, success, errorCode);
        } catch (Exception ignored) {
            // A malformed telemetry payload must never block the business event.
        }
    }

    private String firstText(JsonNode node, String first, String second) {
        String value = node.path(first).asText("");
        return value.isBlank() ? node.path(second).asText("") : value;
    }

    private int parsePromptVersion(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        String digits = value.replaceAll(".*?(\\d+)$", "$1");
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
