package com.storyforge.analytics;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.privacy.PrivacyPolicy;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProductAnalyticsService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ProductAnalyticsService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Records one server-authoritative milestone. Idempotency keys are stable
     * business identifiers, so HTTP retries and Redis redelivery cannot inflate
     * the pilot funnel.
     */
    public void record(
            String eventName,
            Long userId,
            Long storyId,
            Long taskId,
            String idempotencyKey,
            Map<String, ?> properties
    ) {
        if (!ProductEventNames.isAllowed(eventName)) {
            throw new IllegalArgumentException("不支持的产品事件: " + eventName);
        }
        if (userId == null || !StringUtils.hasText(idempotencyKey)) {
            throw new IllegalArgumentException("产品事件缺少用户或幂等键");
        }
        if (!hasCurrentPrivacyConsent(userId)) {
            return;
        }
        String normalizedKey = idempotencyKey.trim();
        if (normalizedKey.length() > 160) {
            throw new IllegalArgumentException("产品事件幂等键过长");
        }
        try {
            jdbc.update("""
                    INSERT INTO product_event
                    (event_name, user_id, story_id, task_id, idempotency_key,
                     properties_json, occurred_time)
                    VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, eventName, userId, storyId, taskId, normalizedKey,
                    properties == null || properties.isEmpty() ? null : write(properties));
        } catch (DuplicateKeyException ignored) {
            // Expected for retried commands and redelivered stream messages.
        }
    }

    public void record(
            String eventName,
            Long userId,
            Long storyId,
            Long taskId,
            String idempotencyKey
    ) {
        record(eventName, userId, storyId, taskId, idempotencyKey, Map.of());
    }

    private String write(Map<String, ?> properties) {
        try {
            return mapper.writeValueAsString(properties);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("产品事件属性无法序列化", exception);
        }
    }

    private boolean hasCurrentPrivacyConsent(Long userId) {
        return jdbc.query(
                "SELECT privacy_version FROM sys_user WHERE id = ?",
                (result, row) -> result.getString("privacy_version"),
                userId
        ).stream().anyMatch(PrivacyPolicy.CURRENT_VERSION::equals);
    }
}
