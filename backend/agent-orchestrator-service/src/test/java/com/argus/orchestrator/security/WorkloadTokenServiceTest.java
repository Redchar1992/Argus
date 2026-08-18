package com.argus.orchestrator.security;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkloadTokenServiceTest {

    private final WorkloadTokenService service = new WorkloadTokenService(
            "", "", "", "urn:argus:workload", "agent-orchestrator",
            List.of("argus-screening-tools", "argus-case-service"), 60,
            new MockEnvironment());

    @Test
    void tokenIsShortLivedAudienceBoundAndCarriesSignedActor() throws Exception {
        String authorization = service.authorizationFor("argus-screening-tools", "gray");
        SignedJWT jwt = SignedJWT.parse(authorization.substring("Bearer ".length()));

        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        assertEquals("demo-workload-v1", jwt.getHeader().getKeyID());
        assertEquals(List.of("argus-screening-tools"), jwt.getJWTClaimsSet().getAudience());
        assertEquals("workload", jwt.getJWTClaimsSet().getStringClaim("token_type"));
        assertEquals("SERVICE", jwt.getJWTClaimsSet().getStringClaim("role"));
        assertEquals("gray", jwt.getJWTClaimsSet().getStringClaim("actor"));
        long lifetime = Duration.between(jwt.getJWTClaimsSet().getIssueTime().toInstant(),
                jwt.getJWTClaimsSet().getExpirationTime().toInstant()).toSeconds();
        assertEquals(60, lifetime);
        assertTrue(jwt.getJWTClaimsSet().getExpirationTime().toInstant().isAfter(Instant.now()));
    }

    @Test
    void rejectsUnconfiguredAudienceAndPublishesPublicOnlyKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> service.authorizationFor("argus-auth", "gray"));
        String jwks = service.publicJwkSet().toString();
        assertTrue(jwks.contains("demo-workload-v1"));
        assertFalse(jwks.contains("\"d\""));
    }
}
