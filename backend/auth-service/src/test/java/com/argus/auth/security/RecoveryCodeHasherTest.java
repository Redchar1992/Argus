package com.argus.auth.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryCodeHasherTest {

    @Test
    void normalizesDisplayFormattingAndUsesAKeyedHash() {
        String firstPepper = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] second = new byte[32];
        second[0] = 1;
        String secondPepper = Base64.getEncoder().encodeToString(second);
        RecoveryCodeHasher first = new RecoveryCodeHasher(firstPepper, new MockEnvironment());
        RecoveryCodeHasher another = new RecoveryCodeHasher(secondPepper, new MockEnvironment());

        assertEquals(first.hash("abcd-efgh-jklm-npqr-stuv-wxyz"),
                first.hash("ABCDEFGHJKLMNPQRSTUVWXYZ"));
        assertNotEquals(first.hash("ABCDEFGHJKLMNPQRSTUVWXYZ"),
                another.hash("ABCDEFGHJKLMNPQRSTUVWXYZ"));
    }

    @Test
    void refusesTheShippedPepperInProduction() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        assertThrows(IllegalStateException.class, () -> new RecoveryCodeHasher(
                RecoveryCodeHasher.DEV_PEPPER,
                production));
    }
}
