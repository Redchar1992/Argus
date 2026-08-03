package com.storyforge.auth;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public record AuthRateLimitProperties(
        boolean enabled,

        @NotNull
        WindowType windowType,

        @Valid
        @NotNull
        Limit registration,

        @Valid
        @NotNull
        Limit login,

        @Min(100)
        @Max(100_000)
        int maxEntries
) {

    public enum WindowType {
        FIXED,
        SLIDING
    }

    public record Limit(
            @Min(1)
            @Max(10_000)
            int maxAttempts,

            @NotNull
            Duration window
    ) {

        @AssertTrue(message = "rate limit window must be greater than zero")
        public boolean isWindowPositive() {
            return window != null && window.compareTo(Duration.ofMillis(1)) >= 0;
        }
    }
}
