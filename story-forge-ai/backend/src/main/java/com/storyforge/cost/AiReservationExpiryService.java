package com.storyforge.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Releases workflow reservations that remained in human review past their TTL. */
@Service
public class AiReservationExpiryService {
    private static final Logger log = LoggerFactory.getLogger(AiReservationExpiryService.class);
    private final AiQuotaService quota;
    private final AiCreditService credits;

    public AiReservationExpiryService(AiQuotaService quota, AiCreditService credits) {
        this.quota = quota;
        this.credits = credits;
    }

    @Scheduled(fixedDelayString = "${app.ai.quota-expiry-interval-ms:60000}")
    public void releaseExpiredReservations() {
        for (AiQuotaService.ExpiredReservation reservation : quota.findExpiredReservations(100)) {
            try {
                credits.release(
                        reservation.userId(),
                        null,
                        reservation.idempotencyKey(),
                        reservation.credits(),
                        "人工审核超时，自动释放 AI 额度"
                );
            } catch (RuntimeException exception) {
                log.warn("自动释放 AI 额度失败: key={}", reservation.idempotencyKey(), exception);
            }
        }
    }
}
