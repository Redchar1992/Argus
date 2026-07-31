package com.storyforge.cost;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import com.storyforge.common.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiCreditService {
    private static final long WELCOME_CREDITS = 100;
    private final JdbcTemplate jdbc;

    public AiCreditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void ensureWallet(Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_ai_wallet WHERE user_id=?", Integer.class, userId);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO user_ai_wallet (user_id, available_credits, frozen_credits, consumed_credits, updated_time) VALUES (?, ?, 0, 0, CURRENT_TIMESTAMP)", userId, WELCOME_CREDITS);
            insertLog(userId, null, "GRANT", WELCOME_CREDITS, 0, WELCOME_CREDITS, "新用户体验额度", "welcome:" + userId);
        }
    }

    @Transactional
    public void consume(Long userId, String idempotencyKey, long amount, String description) {
        if (amount <= 0) throw new IllegalArgumentException("积分消耗必须大于0");
        ensureWallet(userId);
        if (existsLog(idempotencyKey)) return;
        AiWalletResponse wallet = get(userId);
        if (wallet.availableCredits() < amount) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "AI_CREDITS_INSUFFICIENT", "AI 积分余额不足");
        }
        jdbc.update("UPDATE user_ai_wallet SET available_credits=available_credits-?, consumed_credits=consumed_credits+?, updated_time=CURRENT_TIMESTAMP WHERE user_id=?", amount, amount, userId);
        insertLog(userId, null, "SETTLE", -amount, wallet.availableCredits(), wallet.availableCredits() - amount, description, idempotencyKey);
    }

    @Transactional
    public void freeze(Long userId, Long taskId, String idempotencyKey, long amount, String description) {
        if (amount <= 0) throw new IllegalArgumentException("冻结积分必须大于0");
        ensureWallet(userId);
        if (existsLog(idempotencyKey)) return;
        AiWalletResponse wallet = get(userId);
        if (wallet.availableCredits() < amount) throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "AI_CREDITS_INSUFFICIENT", "AI 积分余额不足");
        jdbc.update("UPDATE user_ai_wallet SET available_credits=available_credits-?, frozen_credits=frozen_credits+?, updated_time=CURRENT_TIMESTAMP WHERE user_id=?", amount, amount, userId);
        insertLog(userId, taskId, "FREEZE", -amount, wallet.availableCredits(), wallet.availableCredits() - amount, description, idempotencyKey);
    }

    @Transactional
    public void release(Long userId, Long taskId, String idempotencyKey, long amount, String description) {
        ensureWallet(userId);
        if (existsLog(idempotencyKey)) return;
        AiWalletResponse wallet = get(userId);
        long actual = Math.min(amount, wallet.frozenCredits());
        jdbc.update("UPDATE user_ai_wallet SET frozen_credits=frozen_credits-?, available_credits=available_credits+?, updated_time=CURRENT_TIMESTAMP WHERE user_id=?", actual, actual, userId);
        insertLog(userId, taskId, "RELEASE", actual, wallet.availableCredits(), wallet.availableCredits() + actual, description, idempotencyKey);
    }

    public AiWalletResponse get(Long userId) {
        ensureWallet(userId);
        return jdbc.queryForObject("SELECT * FROM user_ai_wallet WHERE user_id=?", (rs, row) -> new AiWalletResponse(
                rs.getLong("user_id"), rs.getLong("available_credits"), rs.getLong("frozen_credits"),
                rs.getLong("consumed_credits"), timestamp(rs.getTimestamp("updated_time"))), userId);
    }

    public List<AiCreditLogResponse> logs(Long userId) {
        ensureWallet(userId);
        return jdbc.query("SELECT * FROM user_ai_credit_log WHERE user_id=? ORDER BY created_time DESC, id DESC LIMIT 200", (rs, row) -> new AiCreditLogResponse(
                rs.getLong("id"), rs.getString("operation_type"), rs.getLong("amount"), rs.getLong("balance_before"),
                rs.getLong("balance_after"), rs.getString("description"), timestamp(rs.getTimestamp("created_time"))), userId);
    }

    private boolean existsLog(String key) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM user_ai_credit_log WHERE idempotency_key=?", Integer.class, key);
        return count != null && count > 0;
    }

    private void insertLog(Long userId, Long taskId, String operation, long amount, long before, long after,
            String description, String key) {
        jdbc.update("INSERT INTO user_ai_credit_log (user_id, task_id, operation_type, amount, balance_before, balance_after, idempotency_key, description, created_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                userId, taskId, operation, amount, before, after, key, description);
    }

    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
