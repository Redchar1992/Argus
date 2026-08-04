package com.storyforge.cost;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.List;

import com.storyforge.common.exception.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistent daily/monthly quota accounting for platform-funded AI calls. */
@Service
public class AiQuotaService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final AiPricingService pricing;

    public AiQuotaService(JdbcTemplate jdbc, AiPricingService pricing) {
        this.jdbc = jdbc;
        this.pricing = pricing;
    }

    @Transactional
    public void reserve(Long userId, Long taskId, String idempotencyKey, long amount) {
        reserve(userId, taskId, idempotencyKey, amount, null);
    }

    @Transactional
    public void reserve(Long userId, Long taskId, String idempotencyKey, long amount,
            LocalDateTime expiresAt) {
        if (amount <= 0 || existsReservation(idempotencyKey)) return;
        AiEntitlementResponse entitlement = pricing.ensureEntitlement(userId);
        if (!"ACTIVE".equalsIgnoreCase(entitlement.status())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_PLAN_DISABLED", "当前 AI 使用额度已停用");
        }
        checkConcurrentTasks(userId, taskId, entitlement.maxConcurrentTasks());
        LocalDate today = LocalDate.now(ZONE);
        LocalDate month = YearMonth.from(today).atDay(1);
        Period daily = lockPeriod(userId, "DAILY", today, entitlement.dailyLimit());
        Period monthly = lockPeriod(userId, "MONTHLY", month, entitlement.monthlyLimit());
        if (daily.used() + amount > daily.limit()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "AI_DAILY_LIMIT_REACHED", "已达到今日 AI 额度上限");
        }
        if (monthly.used() + amount > monthly.limit()) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "AI_MONTHLY_LIMIT_REACHED", "已达到本月 AI 额度上限");
        }
        increaseReserved(userId, "DAILY", today, amount);
        increaseReserved(userId, "MONTHLY", month, amount);
        try {
            jdbc.update("""
                    INSERT INTO ai_quota_reservation
                    (idempotency_key, user_id, daily_start, monthly_start, credits, status,
                     expires_time, created_time, updated_time)
                    VALUES (?, ?, ?, ?, ?, 'FROZEN', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, idempotencyKey, userId, today, month, amount, expiresAt);
        } catch (DuplicateKeyException ignored) {
            // A concurrent replay owns the same reservation. The outer
            // transaction will roll back these increments if the insert lost.
            throw ignored;
        }
    }

    @Transactional
    public void release(Long userId, String idempotencyKey, long amount) {
        QuotaReservation reservation = lockReservation(userId, idempotencyKey);
        if (reservation == null || !"FROZEN".equals(reservation.status())) return;
        long released = Math.min(amount, reservation.credits());
        decreaseReserved(userId, "DAILY", reservation.dailyStart(), released);
        decreaseReserved(userId, "MONTHLY", reservation.monthlyStart(), released);
        jdbc.update("""
                UPDATE ai_quota_reservation
                SET status='RELEASED', updated_time=CURRENT_TIMESTAMP
                WHERE idempotency_key=? AND user_id=?
                """, idempotencyKey, userId);
    }

    @Transactional
    public void settle(Long userId, String idempotencyKey, long reservedAmount, long actualAmount) {
        QuotaReservation reservation = lockReservation(userId, idempotencyKey);
        if (reservation == null || !"FROZEN".equals(reservation.status())) return;
        // The reservation row is the source of truth. This also protects a
        // long-running task from a later pricing-table update.
        long reserved = reservation.credits();
        long actual = Math.max(0, Math.min(reserved, actualAmount));
        settlePeriod(userId, "DAILY", reservation.dailyStart(), reserved, actual);
        settlePeriod(userId, "MONTHLY", reservation.monthlyStart(), reserved, actual);
        jdbc.update("""
                UPDATE ai_quota_reservation
                SET status='SETTLED', updated_time=CURRENT_TIMESTAMP
                WHERE idempotency_key=? AND user_id=?
                """, idempotencyKey, userId);
    }

    public QuotaSnapshot snapshot(Long userId) {
        AiEntitlementResponse entitlement = pricing.ensureEntitlement(userId);
        LocalDate today = LocalDate.now(ZONE);
        LocalDate month = YearMonth.from(today).atDay(1);
        Period daily = readPeriod(userId, "DAILY", today);
        Period monthly = readPeriod(userId, "MONTHLY", month);
        return new QuotaSnapshot(
                entitlement.planCode(), entitlement.dailyLimit(), entitlement.monthlyLimit(),
                Math.max(0, entitlement.dailyLimit() - daily.used()),
                Math.max(0, entitlement.monthlyLimit() - monthly.used()),
                entitlement.maxConcurrentTasks());
    }

    public boolean isFrozen(Long userId, String idempotencyKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ai_quota_reservation
                WHERE user_id=? AND idempotency_key=? AND status='FROZEN'
                  AND (expires_time IS NULL OR expires_time > CURRENT_TIMESTAMP)
                """, Integer.class, userId, idempotencyKey);
        return count != null && count > 0;
    }

    public List<ExpiredReservation> findExpiredReservations(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query("""
                SELECT idempotency_key, user_id, credits, expires_time
                FROM ai_quota_reservation
                WHERE status='FROZEN' AND expires_time IS NOT NULL
                  AND expires_time <= CURRENT_TIMESTAMP
                ORDER BY expires_time, idempotency_key
                LIMIT ?
                """, (rs, row) -> new ExpiredReservation(
                rs.getString("idempotency_key"),
                rs.getLong("user_id"),
                rs.getLong("credits"),
                timestamp(rs.getTimestamp("expires_time"))), safeLimit);
    }

    private boolean existsReservation(String idempotencyKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_quota_reservation WHERE idempotency_key=?",
                Integer.class, idempotencyKey);
        return count != null && count > 0;
    }

    private QuotaReservation lockReservation(Long userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT idempotency_key, daily_start, monthly_start, credits, status
                FROM ai_quota_reservation
                WHERE idempotency_key=? AND user_id=? FOR UPDATE
                """, (rs, row) -> new QuotaReservation(
                rs.getString("idempotency_key"), rs.getDate("daily_start").toLocalDate(),
                rs.getDate("monthly_start").toLocalDate(), rs.getLong("credits"),
                rs.getString("status")), idempotencyKey, userId)
                .stream().findFirst().orElse(null);
    }

    private void checkConcurrentTasks(Long userId, Long taskId, int limit) {
        if (limit <= 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AI_PLAN_DISABLED", "当前 AI 并发额度已停用");
        }
        Integer active;
        if (taskId == null) {
            active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ai_task
                    WHERE user_id=? AND status IN ('WAITING', 'RUNNING')
                    """, Integer.class, userId);
        } else {
            active = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM ai_task
                    WHERE user_id=? AND id<>? AND status IN ('WAITING', 'RUNNING')
                    """, Integer.class, userId, taskId);
        }
        if (active != null && active >= limit) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "AI_CONCURRENCY_LIMIT", "已有 AI 任务正在运行，请稍后再试");
        }
    }

    private Period lockPeriod(Long userId, String type, LocalDate start, long limit) {
        ensurePeriod(userId, type, start);
        return jdbc.queryForObject("""
                SELECT reserved_credits, consumed_credits
                FROM user_ai_usage_period
                WHERE user_id=? AND period_type=? AND period_start=? FOR UPDATE
                """, (rs, row) -> new Period(limit,
                rs.getLong("reserved_credits") + rs.getLong("consumed_credits")),
                userId, type, start);
    }

    private Period readPeriod(Long userId, String type, LocalDate start) {
        ensurePeriod(userId, type, start);
        return jdbc.queryForObject("""
                SELECT reserved_credits, consumed_credits
                FROM user_ai_usage_period
                WHERE user_id=? AND period_type=? AND period_start=?
                """, (rs, row) -> new Period(0,
                rs.getLong("reserved_credits") + rs.getLong("consumed_credits")),
                userId, type, start);
    }

    private void ensurePeriod(Long userId, String type, LocalDate start) {
        try {
            jdbc.update("""
                    INSERT INTO user_ai_usage_period
                    (user_id, period_type, period_start, reserved_credits, consumed_credits, task_count, updated_time)
                    VALUES (?, ?, ?, 0, 0, 0, CURRENT_TIMESTAMP)
                    """, userId, type, start);
        } catch (DuplicateKeyException ignored) {
            // The row already exists or another request created it concurrently.
        }
    }

    private void increaseReserved(Long userId, String type, LocalDate start, long amount) {
        jdbc.update("""
                UPDATE user_ai_usage_period
                SET reserved_credits=reserved_credits+?, updated_time=CURRENT_TIMESTAMP
                WHERE user_id=? AND period_type=? AND period_start=?
                """, amount, userId, type, start);
    }

    private void decreaseReserved(Long userId, String type, LocalDate start, long amount) {
        jdbc.update("""
                UPDATE user_ai_usage_period
                SET reserved_credits=CASE WHEN reserved_credits >= ? THEN reserved_credits-? ELSE 0 END,
                    updated_time=CURRENT_TIMESTAMP
                WHERE user_id=? AND period_type=? AND period_start=?
                """, amount, amount, userId, type, start);
    }

    private void settlePeriod(Long userId, String type, LocalDate start, long reserved, long actual) {
        jdbc.update("""
                UPDATE user_ai_usage_period
                SET reserved_credits=CASE WHEN reserved_credits >= ? THEN reserved_credits-? ELSE 0 END,
                    consumed_credits=consumed_credits+?, updated_time=CURRENT_TIMESTAMP
                WHERE user_id=? AND period_type=? AND period_start=?
                """, reserved, reserved, actual, userId, type, start);
    }

    public record QuotaSnapshot(
            String planCode,
            long dailyLimit,
            long monthlyLimit,
            long dailyRemaining,
            long monthlyRemaining,
            int maxConcurrentTasks
    ) { }

    public record ExpiredReservation(
            String idempotencyKey,
            Long userId,
            long credits,
            LocalDateTime expiresAt
    ) { }

    private record Period(long limit, long used) { }

    private record QuotaReservation(
            String idempotencyKey,
            LocalDate dailyStart,
            LocalDate monthlyStart,
            long credits,
            String status
    ) { }

    private LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
