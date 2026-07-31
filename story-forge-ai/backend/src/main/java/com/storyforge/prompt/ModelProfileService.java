package com.storyforge.prompt;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ModelProfileService {
    private final JdbcTemplate jdbc;

    public ModelProfileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ModelProfileResponse create(Long userId, ModelProfileRequest request) {
        jdbc.update("INSERT INTO model_profile (profile_key, provider, model_name, temperature, max_tokens, secret_reference, status, created_by, created_time, updated_time) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                request.profileKey().trim(), request.provider().trim(), request.modelName().trim(), request.temperature() == null ? BigDecimal.valueOf(.2) : request.temperature(), request.maxTokens() == null ? 3000 : request.maxTokens(), request.secretReference(), userId);
        return list(userId).stream().filter(item -> item.profileKey().equals(request.profileKey().trim())).findFirst().orElseThrow();
    }

    public List<ModelProfileResponse> list(Long userId) {
        return jdbc.query("SELECT * FROM model_profile WHERE created_by=? ORDER BY profile_key", (rs, row) -> new ModelProfileResponse(
                rs.getLong("id"), rs.getString("profile_key"), rs.getString("provider"), rs.getString("model_name"), rs.getBigDecimal("temperature"), rs.getInt("max_tokens"), rs.getString("secret_reference"), rs.getString("status"), timestamp(rs.getTimestamp("updated_time"))), userId);
    }

    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
