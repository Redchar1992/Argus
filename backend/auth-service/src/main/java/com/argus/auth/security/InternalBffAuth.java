package com.argus.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Temporary workload credential, complemented by authenticated TLS in production. */
@Component
public class InternalBffAuth {

    public static final String DEV_SECRET = "argus-dev-internal-bff-secret-change-me";
    private final byte[] expected;

    public InternalBffAuth(
            @Value("${argus.internal.bff-secret:" + DEV_SECRET + "}") String secret,
            Environment environment) {
        if (secret.length() < 32) throw new IllegalStateException("ARGUS_INTERNAL_BFF_SECRET must be at least 32 characters");
        if (isProduction(environment) && DEV_SECRET.equals(secret)) {
            throw new IllegalStateException("The shipped BFF workload secret cannot be used in production");
        }
        this.expected = secret.getBytes(StandardCharsets.UTF_8);
    }

    public void require(String presented) {
        byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid BFF workload credential");
        }
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
