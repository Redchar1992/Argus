package com.argus.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned AES-256-GCM envelope for identity secrets such as TOTP seeds. */
@Component
public class IdentitySecretCipher {

    static final String DEV_KEY_ID = "dev-v1";
    private static final String DEV_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final String primaryKeyId;
    private final Map<String, SecretKeySpec> keys;
    private final SecureRandom random = new SecureRandom();

    public IdentitySecretCipher(
            @Value("${argus.identity.primary-key-id:" + DEV_KEY_ID + "}") String primaryKeyId,
            @Value("${argus.identity.keys:" + DEV_KEY_ID + ":" + DEV_KEY + "}") String configuredKeys,
            Environment environment) {
        this.primaryKeyId = primaryKeyId;
        this.keys = parseKeys(configuredKeys);
        if (!keys.containsKey(primaryKeyId)) {
            throw new IllegalStateException("ARGUS_IDENTITY_PRIMARY_KEY_ID is not present in ARGUS_IDENTITY_KEYS");
        }
        if (isProduction(environment) && keys.containsKey(DEV_KEY_ID)) {
            throw new IllegalStateException("The shipped development identity key cannot be used in production");
        }
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(primaryKeyId), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(primaryKeyId));
            byte[] encryptedAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return String.join(".", "v1", primaryKeyId, URL_ENCODER.encodeToString(iv),
                    URL_ENCODER.encodeToString(encryptedAndTag));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Unable to encrypt identity secret", failure);
        }
    }

    public String decrypt(String envelope) {
        String[] parts = envelope == null ? new String[0] : envelope.split("\\.", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])) {
            throw new IllegalArgumentException("Invalid identity secret envelope");
        }
        SecretKeySpec key = keys.get(parts[1]);
        if (key == null) throw new IllegalStateException("Identity secret references an unavailable key");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, URL_DECODER.decode(parts[2])));
            cipher.updateAAD(aad(parts[1]));
            return new String(cipher.doFinal(URL_DECODER.decode(parts[3])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid identity secret envelope", failure);
        }
    }

    public String primaryKeyId() {
        return primaryKeyId;
    }

    /** Returns true when a valid envelope was written with a retained, non-primary key. */
    public boolean needsRotation(String envelope) {
        String keyId = envelopeKeyId(envelope);
        if (keyId.isEmpty()) throw new IllegalArgumentException("Invalid identity secret envelope");
        return !primaryKeyId.equals(keyId);
    }

    /** Re-encrypts an old envelope with the current primary key without exposing plaintext. */
    public String rotate(String envelope) {
        return needsRotation(envelope) ? encrypt(decrypt(envelope)) : envelope;
    }

    public String envelopeKeyId(String envelope) {
        String[] parts = envelope == null ? new String[0] : envelope.split("\\.", -1);
        if (parts.length != 4 || !"v1".equals(parts[0])) return "";
        return parts[1];
    }

    private static byte[] aad(String keyId) {
        return ("argus-identity:v1:" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, SecretKeySpec> parseKeys(String value) {
        Map<String, SecretKeySpec> parsed = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            int separator = item.indexOf(':');
            if (separator <= 0 || separator == item.length() - 1) {
                throw new IllegalStateException("ARGUS_IDENTITY_KEYS must use kid:base64 entries");
            }
            String keyId = item.substring(0, separator).trim();
            if (!keyId.matches("[A-Za-z0-9_-]{1,32}") || parsed.containsKey(keyId)) {
                throw new IllegalStateException("ARGUS_IDENTITY_KEYS contains an invalid or duplicate key id");
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(item.substring(separator + 1).trim());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException("ARGUS_IDENTITY_KEYS contains invalid base64", invalid);
            }
            if (key.length != 32) throw new IllegalStateException("Every identity key must be exactly 32 bytes");
            parsed.put(keyId, new SecretKeySpec(key, "AES"));
        }
        return Map.copyOf(parsed);
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
