package com.argus.security.jwt;

import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsaKeyRingTest {

    @Test
    void signsWithKidAndPublishesPublicOnlyJwks() {
        RsaKeyRing signer = RsaKeyRing.signing("", "", "", KeyPurpose.AUTH, false);
        String token = signer.sign(claims("urn:argus:auth", "argus-orchestrator", "user"));
        JwtDecoder decoder = JwtSecurity.decoder(new JwtSecurity.TrustRoute(
                signer.publicKeys(),
                JwtSecurity.validator("urn:argus:auth", "argus-orchestrator", "user")));

        assertEquals("gray", decoder.decode(token).getSubject());
        String jwks = signer.publicJwkSet().toString();
        assertTrue(jwks.contains("demo-auth-v1"));
        assertFalse(jwks.contains("\"d\""));
        assertFalse(jwks.contains("private"));
    }

    @Test
    void rejectsWrongAudienceAndProductionFallbackKeys() {
        RsaKeyRing signer = RsaKeyRing.signing("", "", "", KeyPurpose.AUTH, false);
        String token = signer.sign(claims("urn:argus:auth", "somewhere-else", "user"));
        JwtDecoder decoder = JwtSecurity.decoder(new JwtSecurity.TrustRoute(
                signer.publicKeys(),
                JwtSecurity.validator("urn:argus:auth", "argus-orchestrator", "user")));

        assertThrows(JwtException.class, () -> decoder.decode(token));
        assertThrows(IllegalStateException.class,
                () -> RsaKeyRing.signing("", "", "", KeyPurpose.AUTH, true));
        assertThrows(IllegalStateException.class,
                () -> RsaKeyRing.verification("", KeyPurpose.AUTH, true));
    }

    @Test
    void overlappingPublicRingAcceptsTokensAcrossPrimaryRotation() {
        String privateMaterial = DemoKeyMaterial.privateKeys(KeyPurpose.AUTH).split(":", 2)[1];
        String publicMaterial = DemoKeyMaterial.publicKeys(KeyPurpose.AUTH).split(":", 2)[1];
        String privateRing = "old-v1:" + privateMaterial + ",new-v2:" + privateMaterial;
        String publicRing = "old-v1:" + publicMaterial + ",new-v2:" + publicMaterial;
        RsaKeyRing oldSigner = RsaKeyRing.signing("old-v1", privateRing, publicRing,
                KeyPurpose.AUTH, false);
        RsaKeyRing newSigner = RsaKeyRing.signing("new-v2", privateRing, publicRing,
                KeyPurpose.AUTH, false);
        JwtDecoder decoder = JwtSecurity.decoder(new JwtSecurity.TrustRoute(
                newSigner.publicKeys(),
                JwtSecurity.validator("urn:argus:auth", "argus-orchestrator", "user")));

        assertEquals("gray", decoder.decode(oldSigner.sign(
                claims("urn:argus:auth", "argus-orchestrator", "user"))).getSubject());
        assertEquals("gray", decoder.decode(newSigner.sign(
                claims("urn:argus:auth", "argus-orchestrator", "user"))).getSubject());
        assertTrue(newSigner.publicJwkSet().toString().contains("old-v1"));
        assertTrue(newSigner.publicJwkSet().toString().contains("new-v2"));
    }

    private JWTClaimsSet claims(String issuer, String audience, String tokenType) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("gray")
                .audience(List.of(audience))
                .claim("token_type", tokenType)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60)))
                .build();
    }
}
