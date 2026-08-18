package com.argus.auth.security;

import com.argus.auth.model.UserAccount;
import com.argus.security.jwt.JwtSecurity;
import com.argus.security.jwt.KeyPurpose;
import com.argus.security.jwt.RsaKeyRing;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues versioned RS256 user access tokens and validates them with the public half of the
 * configured key ring. Retired public keys remain trusted during a zero-downtime rotation;
 * only the primary private key is used for new tokens.
 */
@Service
public class JwtService {

    private static final String ORCHESTRATOR_AUDIENCE = "argus-orchestrator";

    private final RsaKeyRing keyRing;
    private final JwtDecoder decoder;
    private final String issuer;
    private final List<String> audiences;
    private final long expirySeconds;

    public JwtService(
            @Value("${argus.jwt.primary-key-id:}") String primaryKeyId,
            @Value("${argus.jwt.private-keys:}") String privateKeys,
            @Value("${argus.jwt.public-keys:}") String publicKeys,
            @Value("${argus.jwt.issuer:urn:argus:auth}") String issuer,
            @Value("${argus.jwt.audiences:argus-orchestrator,argus-admin-api}") List<String> audiences,
            @Value("${argus.jwt.expiry-seconds:3600}") long expirySeconds,
            Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
        if (expirySeconds < 60 || expirySeconds > 86_400) {
            throw new IllegalStateException("User access-token expiry must be between 60 and 86400 seconds");
        }
        this.audiences = audiences.stream().map(String::trim).filter(value -> !value.isBlank()).toList();
        if (!this.audiences.contains(ORCHESTRATOR_AUDIENCE)) {
            throw new IllegalStateException("User access-token audiences must include " + ORCHESTRATOR_AUDIENCE);
        }
        this.issuer = issuer;
        this.expirySeconds = expirySeconds;
        this.keyRing = RsaKeyRing.signing(primaryKeyId, privateKeys, publicKeys,
                KeyPurpose.AUTH, production);
        this.decoder = JwtSecurity.decoder(new JwtSecurity.TrustRoute(
                keyRing.publicKeys(),
                JwtSecurity.validator(issuer, ORCHESTRATOR_AUDIENCE, JwtSecurity.USER_TOKEN_TYPE)));
    }

    public String issue(UserAccount user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(user.getUsername())
                .audience(audiences)
                .jwtID(UUID.randomUUID().toString())
                .claim("token_type", JwtSecurity.USER_TOKEN_TYPE)
                .claim("role", user.getRole().name())
                .claim("uid", user.getId())
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(expirySeconds)))
                .build();
        return keyRing.sign(claims);
    }

    public Jwt parse(String token) {
        return decoder.decode(token);
    }

    public Map<String, Object> publicJwkSet() {
        return keyRing.publicJwkSet();
    }

    public long getExpirySeconds() {
        return expirySeconds;
    }
}
