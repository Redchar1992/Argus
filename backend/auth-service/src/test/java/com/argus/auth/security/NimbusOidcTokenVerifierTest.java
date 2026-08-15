package com.argus.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NimbusOidcTokenVerifierTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String AUDIENCE = "argus-client";

    @Test
    void acceptsOnlyTheConfiguredAudienceAndExpectedNonce() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(jwt(List.of(AUDIENCE), "expected-nonce", "subject-1"));
        NimbusOidcTokenVerifier verifier = new NimbusOidcTokenVerifier(decoder, ISSUER, AUDIENCE);

        OidcTokenVerifier.OidcIdentity identity = verifier.verify("valid", "expected-nonce");
        assertEquals(ISSUER, identity.issuer());
        assertEquals("subject-1", identity.subject());
        assertEquals("user@example.com", identity.email());
    }

    @Test
    void rejectsAudienceNonceAndSubjectMismatch() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("wrong-audience")).thenReturn(jwt(List.of("another-client"), "nonce", "subject"));
        when(decoder.decode("wrong-nonce")).thenReturn(jwt(List.of(AUDIENCE), "other-nonce", "subject"));
        when(decoder.decode("blank-subject")).thenReturn(jwt(List.of(AUDIENCE), "nonce", ""));
        NimbusOidcTokenVerifier verifier = new NimbusOidcTokenVerifier(decoder, ISSUER, AUDIENCE);

        assertThrows(ResponseStatusException.class, () -> verifier.verify("wrong-audience", "nonce"));
        assertThrows(ResponseStatusException.class, () -> verifier.verify("wrong-nonce", "nonce"));
        assertThrows(ResponseStatusException.class, () -> verifier.verify("blank-subject", "nonce"));
    }

    private static Jwt jwt(List<String> audience, String nonce, String subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("provider-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(audience)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim("nonce", nonce)
                .claim("email", "user@example.com")
                .build();
    }
}
