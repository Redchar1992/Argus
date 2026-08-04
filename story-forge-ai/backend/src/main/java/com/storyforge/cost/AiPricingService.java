package com.storyforge.cost;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Platform-owned AI price and entitlement lookup. */
@Service
public class AiPricingService {
    private static final String DEFAULT_PLAN = "PILOT";
    private static final long DEFAULT_DAILY_LIMIT = 100L;
    private static final long DEFAULT_MONTHLY_LIMIT = 1_000L;
    private static final int DEFAULT_CONCURRENCY = 1;

    private final JdbcTemplate jdbc;

    public AiPricingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long credits(String operationType) {
        Long value = jdbc.queryForObject(
                "SELECT credits FROM ai_operation_pricing WHERE operation_type=? AND enabled=TRUE",
                Long.class, operationType);
        if (value == null || value <= 0) {
            throw new IllegalStateException("未配置或已停用的 AI 操作价格: " + operationType);
        }
        return value;
    }

    public List<AiPricingResponse> list() {
        return jdbc.query(
                "SELECT operation_type, credits, enabled FROM ai_operation_pricing ORDER BY operation_type",
                (rs, row) -> new AiPricingResponse(
                        rs.getString("operation_type"), rs.getLong("credits"), rs.getBoolean("enabled")));
    }

    @Transactional
    public AiEntitlementResponse ensureEntitlement(Long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_ai_entitlement WHERE user_id=?", Integer.class, userId);
        if (count == null || count == 0) {
            try {
                jdbc.update("""
                        INSERT INTO user_ai_entitlement
                        (user_id, plan_code, daily_limit, monthly_limit, max_concurrent_tasks, status, updated_time)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                        """, userId, DEFAULT_PLAN, DEFAULT_DAILY_LIMIT,
                        DEFAULT_MONTHLY_LIMIT, DEFAULT_CONCURRENCY);
            } catch (DuplicateKeyException ignored) {
                // Another request initialized the entitlement concurrently.
            }
        }
        return entitlement(userId);
    }

    public AiEntitlementResponse entitlement(Long userId) {
        return jdbc.queryForObject("""
                SELECT plan_code, daily_limit, monthly_limit, max_concurrent_tasks, status
                FROM user_ai_entitlement WHERE user_id=?
                """, (rs, row) -> new AiEntitlementResponse(
                rs.getString("plan_code"), rs.getLong("daily_limit"),
                rs.getLong("monthly_limit"), rs.getInt("max_concurrent_tasks"),
                rs.getString("status")), userId);
    }
}
