package com.argus.security.jwt;

import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decoder routing and issuer/audience/token-class validation shared by resource services. */
public final class JwtSecurity {

    public static final String USER_TOKEN_TYPE = "user";
    public static final String WORKLOAD_TOKEN_TYPE = "workload";

    private JwtSecurity() {
    }

    public record TrustRoute(Map<String, RSAPublicKey> keys,
                             OAuth2TokenValidator<Jwt> validator) {
    }

    public static JwtDecoder decoder(TrustRoute... routes) {
        Map<String, JwtDecoder> byKeyId = new LinkedHashMap<>();
        for (TrustRoute route : routes) {
            route.keys().forEach((keyId, publicKey) -> {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        // Access tokens deliberately use RFC 9068's `at+jwt` type. The
                        // token_type claim validator below still separates user/workload trust.
                        .validateType(false)
                        .build();
                decoder.setJwtValidator(route.validator());
                if (byKeyId.putIfAbsent(keyId, decoder) != null) {
                    throw new IllegalStateException("JWT key IDs must be unique across trust domains: " + keyId);
                }
            });
        }
        if (byKeyId.isEmpty()) throw new IllegalStateException("At least one trusted JWT key is required");
        return token -> {
            try {
                String keyId = SignedJWT.parse(token).getHeader().getKeyID();
                JwtDecoder selected = keyId == null ? null : byKeyId.get(keyId);
                if (selected == null) throw new JwtException("JWT uses an unknown or missing key ID");
                return selected.decode(token);
            } catch (JwtException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new JwtException("Malformed JWT", failure);
            }
        };
    }

    public static OAuth2TokenValidator<Jwt> validator(String issuer, String audience,
                                                       String tokenType) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "JWT audience is not permitted for this service", null));
        OAuth2TokenValidator<Jwt> typeValidator = token -> tokenType.equals(token.getClaimAsString("token_type"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "JWT token type is not permitted for this service", null));
        return new DelegatingOAuth2TokenValidator<>(List.of(defaults, audienceValidator, typeValidator));
    }
}
