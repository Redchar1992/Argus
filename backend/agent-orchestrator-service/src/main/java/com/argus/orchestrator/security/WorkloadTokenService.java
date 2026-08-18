package com.argus.orchestrator.security;

import com.argus.security.jwt.JwtSecurity;
import com.argus.security.jwt.KeyPurpose;
import com.argus.security.jwt.RsaKeyRing;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Issues short-lived, audience-bound credentials for orchestrator work, never user tokens. */
@Service
public class WorkloadTokenService {

    public static final String TOOLS_AUDIENCE = "argus-screening-tools";
    public static final String CASES_AUDIENCE = "argus-case-service";

    private final RsaKeyRing keyRing;
    private final String issuer;
    private final String subject;
    private final List<String> audiences;
    private final long ttlSeconds;

    public WorkloadTokenService(
            @Value("${argus.workload.jwt.primary-key-id:}") String primaryKeyId,
            @Value("${argus.workload.jwt.private-keys:}") String privateKeys,
            @Value("${argus.workload.jwt.public-keys:}") String publicKeys,
            @Value("${argus.workload.jwt.issuer:urn:argus:workload}") String issuer,
            @Value("${argus.workload.jwt.subject:agent-orchestrator}") String subject,
            @Value("${argus.workload.jwt.audiences:argus-screening-tools,argus-case-service}") List<String> audiences,
            @Value("${argus.workload.jwt.ttl-seconds:60}") long ttlSeconds,
            Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
        if (ttlSeconds < 15 || ttlSeconds > 300) {
            throw new IllegalStateException("Workload-token TTL must be between 15 and 300 seconds");
        }
        this.audiences = audiences.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
        if (!this.audiences.containsAll(List.of(TOOLS_AUDIENCE, CASES_AUDIENCE))) {
            throw new IllegalStateException("Workload audiences must include tools and case services");
        }
        this.issuer = issuer;
        this.subject = subject;
        this.ttlSeconds = ttlSeconds;
        this.keyRing = RsaKeyRing.signing(primaryKeyId, privateKeys, publicKeys,
                KeyPurpose.WORKLOAD, production);
    }

    public String authorizationFor(String audience, String actor) {
        if (!audiences.contains(audience)) {
            throw new IllegalArgumentException("Workload audience is not allowed: " + audience);
        }
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .jwtID(UUID.randomUUID().toString())
                .claim("token_type", JwtSecurity.WORKLOAD_TOKEN_TYPE)
                .claim("role", "SERVICE")
                .claim("actor", actor == null || actor.isBlank() ? "unknown" : actor)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .build();
        return "Bearer " + keyRing.sign(claims);
    }

    public Map<String, Object> publicJwkSet() {
        return keyRing.publicJwkSet();
    }
}
