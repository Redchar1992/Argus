package com.storyforge.cost;

import com.storyforge.common.security.AuthenticatedUser;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/ai-usage")
public class AiUsageController {
    private final JdbcTemplate jdbc;

    public AiUsageController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public AiUsageSummary summary(@AuthenticationPrincipal AuthenticatedUser user) {
        return jdbc.queryForObject("SELECT COUNT(*) calls, COALESCE(SUM(CASE WHEN success THEN 1 ELSE 0 END),0) successful_calls, COALESCE(SUM(input_tokens),0) input_tokens, COALESCE(SUM(output_tokens),0) output_tokens, COALESCE(SUM(estimated_cost),0) estimated_cost FROM ai_model_usage WHERE user_id=?", (rs, row) -> new AiUsageSummary(
                rs.getLong("calls"), rs.getLong("successful_calls"), rs.getLong("input_tokens"), rs.getLong("output_tokens"), rs.getDouble("estimated_cost")), user.userId());
    }
}
