package com.argus.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdentitySecretCipherTest {

    @Test
    void decryptsRetiredKeysAndWritesOnlyWithPrimaryKey() {
        String oldKey = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] newKeyBytes = new byte[32];
        Arrays.fill(newKeyBytes, (byte) 7);
        String newKey = Base64.getEncoder().encodeToString(newKeyBytes);
        IdentitySecretCipher oldCipher = new IdentitySecretCipher("old", "old:" + oldKey, new MockEnvironment());
        String oldEnvelope = oldCipher.encrypt("TOP-SECRET-SEED");
        IdentitySecretCipher rotated = new IdentitySecretCipher(
                "new", "old:" + oldKey + ",new:" + newKey, new MockEnvironment());

        assertEquals("TOP-SECRET-SEED", rotated.decrypt(oldEnvelope));
        assertEquals(true, rotated.needsRotation(oldEnvelope));
        String reencrypted = rotated.rotate(oldEnvelope);
        assertEquals("new", rotated.envelopeKeyId(reencrypted));
        assertEquals("TOP-SECRET-SEED", rotated.decrypt(reencrypted));
        assertEquals(false, rotated.needsRotation(reencrypted));
        String newEnvelope = rotated.encrypt("TOP-SECRET-SEED");
        assertEquals("new", rotated.envelopeKeyId(newEnvelope));
        assertNotEquals("TOP-SECRET-SEED", newEnvelope);
    }

    @Test
    void rejectsTamperingAndMissingKeys() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        IdentitySecretCipher cipher = new IdentitySecretCipher("v1", "v1:" + key, new MockEnvironment());
        String envelope = cipher.encrypt("secret");
        String[] parts = envelope.split("\\.");
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[3]);
        ciphertext[0] ^= 1;
        parts[3] = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        String tampered = String.join(".", parts);

        assertThrows(IllegalArgumentException.class, () -> cipher.decrypt(tampered));
        IdentitySecretCipher another = new IdentitySecretCipher("v2", "v2:" + key, new MockEnvironment());
        assertThrows(IllegalStateException.class, () -> another.decrypt(envelope));
    }
}
