package com.storyforge.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import com.storyforge.common.exception.ApiException;
import org.junit.jupiter.api.Test;

class InMemoryAuthRateLimiterTest {

    @Test
    void slidingWindowExpiresAttemptsAtTheExactBoundary() {
        MutableClock clock = new MutableClock();
        InMemoryAuthRateLimiter limiter = limiter(
                clock,
                AuthRateLimitProperties.WindowType.SLIDING,
                2,
                Duration.ofMinutes(1)
        );

        limiter.checkRegistration("203.0.113.1");
        limiter.checkRegistration("203.0.113.1");
        assertRateLimited(() -> limiter.checkRegistration("203.0.113.1"));

        clock.advance(Duration.ofMinutes(1));

        assertThatCode(() -> limiter.checkRegistration("203.0.113.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void fixedWindowResetsAndLoginKeysNormalizeUsername() {
        MutableClock clock = new MutableClock();
        InMemoryAuthRateLimiter limiter = limiter(
                clock,
                AuthRateLimitProperties.WindowType.FIXED,
                1,
                Duration.ofSeconds(30)
        );

        limiter.checkLogin("198.51.100.2", " Writer ");
        assertRateLimited(() -> limiter.checkLogin("198.51.100.2", "writer"));
        assertThatCode(() -> limiter.checkLogin("198.51.100.3", "writer"))
                .doesNotThrowAnyException();

        clock.advance(Duration.ofSeconds(30));

        assertThatCode(() -> limiter.checkLogin("198.51.100.2", "WRITER"))
                .doesNotThrowAnyException();
    }

    @Test
    void disabledLimiterNeverConsumesOrRejectsAttempts() {
        MutableClock clock = new MutableClock();
        AuthRateLimitProperties.Limit limit = new AuthRateLimitProperties.Limit(1, Duration.ofHours(1));
        AuthRateLimitProperties properties = new AuthRateLimitProperties(
                false,
                AuthRateLimitProperties.WindowType.SLIDING,
                limit,
                limit,
                100
        );
        InMemoryAuthRateLimiter limiter = new InMemoryAuthRateLimiter(properties, clock);

        for (int i = 0; i < 20; i++) {
            assertThatCode(() -> limiter.checkRegistration("192.0.2.1"))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void entryCapFailsClosedUntilExpiredKeysAreReclaimed() {
        MutableClock clock = new MutableClock();
        InMemoryAuthRateLimiter limiter = limiter(
                clock,
                AuthRateLimitProperties.WindowType.SLIDING,
                1,
                Duration.ofMinutes(5)
        );

        for (int i = 0; i < 100; i++) {
            limiter.checkRegistration("192.0.2." + i);
        }
        assertRateLimited(() -> limiter.checkRegistration("198.51.100.100"));

        clock.advance(Duration.ofMinutes(5));

        assertThatCode(() -> limiter.checkRegistration("198.51.100.100"))
                .doesNotThrowAnyException();
    }

    private InMemoryAuthRateLimiter limiter(
            Clock clock,
            AuthRateLimitProperties.WindowType type,
            int maxAttempts,
            Duration window
    ) {
        AuthRateLimitProperties.Limit limit = new AuthRateLimitProperties.Limit(maxAttempts, window);
        return new InMemoryAuthRateLimiter(
                new AuthRateLimitProperties(true, type, limit, limit, 100),
                clock
        );
    }

    private void assertRateLimited(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    org.assertj.core.api.Assertions.assertThat(exception.getStatus().value()).isEqualTo(429);
                    org.assertj.core.api.Assertions.assertThat(exception.getCode())
                            .isEqualTo(InMemoryAuthRateLimiter.RATE_LIMIT_CODE);
                });
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-08-03T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
