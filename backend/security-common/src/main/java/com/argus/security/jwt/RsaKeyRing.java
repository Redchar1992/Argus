package com.argus.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned RSA signing/verification ring with an explicit active key ID. */
public final class RsaKeyRing {

    private final String primaryKeyId;
    private final RSAPrivateKey signingKey;
    private final Map<String, RSAPublicKey> publicKeys;

    private RsaKeyRing(String primaryKeyId, RSAPrivateKey signingKey,
                       Map<String, RSAPublicKey> publicKeys) {
        this.primaryKeyId = primaryKeyId;
        this.signingKey = signingKey;
        this.publicKeys = Map.copyOf(publicKeys);
    }

    public static RsaKeyRing signing(String primaryKeyId, String configuredPrivateKeys,
                                     String configuredPublicKeys, KeyPurpose purpose,
                                     boolean production) {
        String privateValue = configuredPrivateKeys == null ? "" : configuredPrivateKeys.trim();
        String publicValue = configuredPublicKeys == null ? "" : configuredPublicKeys.trim();
        if (production && (privateValue.isEmpty() || publicValue.isEmpty())) {
            throw new IllegalStateException("Production RSA private and public key rings are required");
        }
        boolean usingDemo = privateValue.isEmpty() || publicValue.isEmpty();
        if (privateValue.isEmpty()) privateValue = DemoKeyMaterial.privateKeys(purpose);
        if (publicValue.isEmpty()) publicValue = DemoKeyMaterial.publicKeys(purpose);
        String selected = primaryKeyId == null || primaryKeyId.isBlank()
                ? DemoKeyMaterial.keyId(purpose) : primaryKeyId.trim();
        if (production && (usingDemo || DemoKeyMaterial.keyId(purpose).equals(selected))) {
            throw new IllegalStateException("The deterministic demo RSA key cannot be used in production");
        }

        Map<String, RSAPrivateKey> privateKeys = parsePrivateKeys(privateValue);
        Map<String, RSAPublicKey> publicKeys = parsePublicKeys(publicValue);
        RSAPrivateKey privateKey = privateKeys.get(selected);
        RSAPublicKey publicKey = publicKeys.get(selected);
        if (privateKey == null || publicKey == null) {
            throw new IllegalStateException("The primary RSA key ID must exist in both key rings");
        }
        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("The primary RSA private/public key pair does not match");
        }
        return new RsaKeyRing(selected, privateKey, publicKeys);
    }

    public static RsaKeyRing verification(String configuredPublicKeys, KeyPurpose purpose,
                                          boolean production) {
        String value = configuredPublicKeys == null ? "" : configuredPublicKeys.trim();
        if (production && value.isEmpty()) {
            throw new IllegalStateException("Production RSA public key ring is required");
        }
        if (value.isEmpty()) value = DemoKeyMaterial.publicKeys(purpose);
        return new RsaKeyRing(null, null, parsePublicKeys(value));
    }

    public String sign(JWTClaimsSet claims) {
        if (signingKey == null || primaryKeyId == null) {
            throw new IllegalStateException("This RSA key ring has no signing key");
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(primaryKeyId)
                        .type(new JOSEObjectType("at+jwt"))
                        .build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException failure) {
            throw new IllegalStateException("Unable to sign RSA JWT", failure);
        }
    }

    public String primaryKeyId() {
        return primaryKeyId;
    }

    public Map<String, RSAPublicKey> publicKeys() {
        return publicKeys;
    }

    /** RFC 7517 public-only key set. Private parameters can never enter this representation. */
    public Map<String, Object> publicJwkSet() {
        List<JWK> keys = new ArrayList<>();
        publicKeys.forEach((keyId, key) -> keys.add(new RSAKey.Builder(key)
                .keyID(keyId)
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build()));
        return new JWKSet(keys).toJSONObject();
    }

    private static Map<String, RSAPrivateKey> parsePrivateKeys(String value) {
        Map<String, RSAPrivateKey> keys = new LinkedHashMap<>();
        parseEntries(value).forEach((keyId, encoded) -> {
            try {
                keys.put(keyId, (RSAPrivateKey) KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(encoded)));
            } catch (Exception failure) {
                throw new IllegalStateException("Invalid PKCS8 RSA private key for " + keyId, failure);
            }
        });
        return keys;
    }

    private static Map<String, RSAPublicKey> parsePublicKeys(String value) {
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        parseEntries(value).forEach((keyId, encoded) -> {
            try {
                keys.put(keyId, (RSAPublicKey) KeyFactory.getInstance("RSA")
                        .generatePublic(new X509EncodedKeySpec(encoded)));
            } catch (Exception failure) {
                throw new IllegalStateException("Invalid X509 RSA public key for " + keyId, failure);
            }
        });
        return keys;
    }

    private static Map<String, byte[]> parseEntries(String value) {
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            int separator = item.indexOf(':');
            String keyId = separator > 0 ? item.substring(0, separator).trim() : "";
            String encoded = separator > 0 ? item.substring(separator + 1).trim() : "";
            if (!keyId.matches("[A-Za-z0-9_-]{1,64}") || encoded.isEmpty()
                    || parsed.containsKey(keyId)) {
                throw new IllegalStateException("RSA key rings require unique kid:base64 entries");
            }
            try {
                parsed.put(keyId, Base64.getDecoder().decode(encoded));
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException("RSA key ring contains invalid base64 for " + keyId, failure);
            }
        }
        if (parsed.isEmpty()) throw new IllegalStateException("RSA key ring cannot be empty");
        return parsed;
    }
}
