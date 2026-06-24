package com.argus.auth.security;

import com.argus.auth.model.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates HS256 JWTs. The signing secret comes from configuration
 * (env in production). Tokens carry the username (subject) and the role claim
 * so downstream services can enforce RBAC without a round-trip.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** The shipped dev default. Issuing real tokens signed with this is a public-key leak. */
    static final String DEFAULT_DEV_SECRET = "argus-dev-secret-change-me-please-32bytes-minimum-length";

    private final SecretKey key;
    private final long expirySeconds;

    public JwtService(
            @Value("${argus.jwt.secret}") String secret,
            @Value("${argus.jwt.expiry-seconds:3600}") long expirySeconds,
            Environment environment) {
        assertSecretConfigured(secret, environment);
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirySeconds = expirySeconds;
    }

    /**
     * auth-service is the token ISSUER, so the shipped dev default is most dangerous here:
     * fail fast on a production profile (never sign tokens with a key that lives in the repo)
     * and log a loud WARN everywhere else (dev / tests legitimately use the default).
     */
    private static void assertSecretConfigured(String secret, Environment environment) {
        if (!DEFAULT_DEV_SECRET.equals(secret)) {
            return;
        }
        boolean production = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                production = true;
                break;
            }
        }
        if (production) {
            throw new IllegalStateException(
                    "ARGUS_JWT_SECRET is still the shipped dev default on a production profile. "
                            + "Set ARGUS_JWT_SECRET to a real secret (>=32 bytes) before deploying.");
        }
        log.warn("ARGUS_JWT_SECRET is using the shipped dev default — this signing key is public. "
                + "Acceptable for dev/test only; set ARGUS_JWT_SECRET before any real deployment.");
    }

    public String issue(UserAccount user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("role", user.getRole().name())
                .claim("uid", user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirySeconds)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }
}
