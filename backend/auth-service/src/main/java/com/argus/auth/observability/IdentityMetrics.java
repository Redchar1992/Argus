package com.argus.auth.observability;

import com.argus.auth.dto.AuthDtos.AuthenticationResponse;
import com.argus.auth.dto.AuthDtos.MfaChallengeResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Function;
import java.util.function.Supplier;

/** Low-cardinality identity metrics. Usernames, tokens and credential IDs are never labels. */
@Component
public class IdentityMetrics {

    private final MeterRegistry registry;

    public IdentityMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public AuthenticationResponse primaryAuthentication(
            String flow,
            Supplier<AuthenticationResponse> operation) {
        return observe(flow, operation, response ->
                response instanceof MfaChallengeResponse ? "mfa_required" : "authenticated");
    }

    public <T> T terminalAuthentication(String flow, String successOutcome, Supplier<T> operation) {
        return observe(flow, operation, ignored -> successOutcome);
    }

    public void recordKeyRotation(String mode) {
        Counter.builder("argus.identity.key.rotation.records")
                .description("Identity secret records re-encrypted with the primary key.")
                .tag("mode", mode)
                .register(registry)
                .increment();
    }

    public void recordKeyRotation(String mode, int count) {
        if (count <= 0) return;
        Counter.builder("argus.identity.key.rotation.records")
                .description("Identity secret records re-encrypted with the primary key.")
                .tag("mode", mode)
                .register(registry)
                .increment(count);
    }

    private <T> T observe(String flow, Supplier<T> operation, Function<T, String> successOutcome) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "error";
        try {
            T result = operation.get();
            outcome = successOutcome.apply(result);
            return result;
        } catch (RuntimeException failure) {
            if (failure instanceof ResponseStatusException response
                    && response.getStatusCode().is4xxClientError()) {
                outcome = "rejected";
            }
            throw failure;
        } finally {
            Counter.builder("argus.identity.auth.attempts")
                    .description("Authentication attempts by bounded flow and outcome.")
                    .tags("flow", flow, "outcome", outcome)
                    .register(registry)
                    .increment();
            sample.stop(Timer.builder("argus.identity.auth.duration")
                    .description("Authentication latency by bounded flow and outcome.")
                    .tags("flow", flow, "outcome", outcome)
                    .register(registry));
        }
    }
}
