package com.argus.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/** Signature, issuer, audience, expiry and nonce validation backed by provider JWKS. */
@Component
@ConditionalOnProperty(name = "argus.oidc.enabled", havingValue = "true")
public class NimbusOidcTokenVerifier implements OidcTokenVerifier {

    private final JwtDecoder decoder;
    private final String issuer;
    private final String audience;

    @Autowired
    public NimbusOidcTokenVerifier(
            @Value("${argus.oidc.issuer}") String issuer,
            @Value("${argus.oidc.audience}") String audience) {
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("ARGUS_OIDC_ISSUER and ARGUS_OIDC_AUDIENCE are required when OIDC is enabled");
        }
        this.issuer = issuer;
        this.audience = audience;
        NimbusJwtDecoder jwtDecoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        this.decoder = jwtDecoder;
    }

    NimbusOidcTokenVerifier(JwtDecoder decoder, String issuer, String audience) {
        this.decoder = decoder;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Override
    public OidcIdentity verify(String idToken, String expectedNonce) {
        try {
            Jwt jwt = decoder.decode(idToken);
            if (!jwt.getAudience().contains(audience)
                    || !constantTimeEquals(expectedNonce, jwt.getClaimAsString("nonce"))) {
                throw unauthorized();
            }
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                throw unauthorized();
            }
            return new OidcIdentity(issuer, subject, jwt.getClaimAsString("email"));
        } catch (JwtException | IllegalArgumentException invalid) {
            throw unauthorized();
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(UNAUTHORIZED, "Invalid OIDC identity token");
    }
}
