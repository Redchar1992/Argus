package com.storyforge.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.storyforge.common.exception.ApiException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A bounded, single-node rate limiter for the invite-only pilot.
 *
 * <p>Each request is charged before authentication so the response does not reveal whether a
 * username exists. This implementation deliberately stays local to one backend process; a shared
 * Redis-backed limiter should replace it before horizontally scaling the backend.</p>
 */
@Service
public class InMemoryAuthRateLimiter {

    static final String RATE_LIMIT_CODE = "AUTH_RATE_LIMITED";

    private final AuthRateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Window> registrationWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> loginWindows = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryAuthRateLimiter(AuthRateLimitProperties properties) {
        this(properties, Clock.systemUTC());
    }

    InMemoryAuthRateLimiter(AuthRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkRegistration(String clientIp) {
        check(
                registrationWindows,
                "register:" + normalizeIp(clientIp),
                properties.registration()
        );
    }

    public void checkLogin(String clientIp, String username) {
        check(
                loginWindows,
                "login:" + normalizeIp(clientIp) + ':' + normalizeUsername(username),
                properties.login()
        );
    }

    private void check(Map<String, Window> windows, String key, AuthRateLimitProperties.Limit limit) {
        if (!properties.enabled()) {
            return;
        }

        long now = clock.millis();
        long windowMillis = limit.window().toMillis();
        Window window = windows.get(key);
        if (window == null) {
            synchronized (windows) {
                window = windows.get(key);
                if (window == null) {
                    ensureCapacity(windows, now, windowMillis);
                    window = new Window(now);
                    windows.put(key, window);
                }
            }
        }
        boolean accepted;
        synchronized (window) {
            accepted = switch (properties.windowType()) {
                case FIXED -> window.tryAcquireFixed(now, windowMillis, limit.maxAttempts());
                case SLIDING -> window.tryAcquireSliding(now, windowMillis, limit.maxAttempts());
            };
        }
        if (!accepted) {
            throw rateLimited();
        }
    }

    private void ensureCapacity(Map<String, Window> windows, long now, long windowMillis) {
        if (windows.size() < properties.maxEntries()) {
            return;
        }

        windows.forEach((key, window) -> {
            if (window.isExpired(now, windowMillis)) {
                windows.remove(key, window);
            }
        });
        if (windows.size() >= properties.maxEntries()) {
            // Fail closed rather than allowing attacker-controlled keys to consume unbounded heap.
            throw rateLimited();
        }
    }

    private ApiException rateLimited() {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                RATE_LIMIT_CODE,
                "请求过于频繁，请稍后重试"
        );
    }

    private String normalizeIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        return clientIp.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return "";
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Window {

        private long fixedWindowStart;
        private int fixedWindowCount;
        private final Deque<Long> attempts = new ArrayDeque<>();
        private long lastSeen;

        private Window(long now) {
            this.fixedWindowStart = now;
            this.lastSeen = now;
        }

        private boolean tryAcquireFixed(long now, long windowMillis, int maxAttempts) {
            if (now - fixedWindowStart >= windowMillis) {
                fixedWindowStart = now;
                fixedWindowCount = 0;
            }
            lastSeen = now;
            if (fixedWindowCount >= maxAttempts) {
                return false;
            }
            fixedWindowCount++;
            return true;
        }

        private boolean tryAcquireSliding(long now, long windowMillis, int maxAttempts) {
            removeExpiredAttempts(now, windowMillis);
            lastSeen = now;
            if (attempts.size() >= maxAttempts) {
                return false;
            }
            attempts.addLast(now);
            return true;
        }

        private boolean isExpired(long now, long windowMillis) {
            synchronized (this) {
                removeExpiredAttempts(now, windowMillis);
                return attempts.isEmpty() && now - lastSeen >= windowMillis;
            }
        }

        private void removeExpiredAttempts(long now, long windowMillis) {
            long cutoff = now - windowMillis;
            while (!attempts.isEmpty() && attempts.peekFirst() <= cutoff) {
                attempts.removeFirst();
            }
        }
    }
}
