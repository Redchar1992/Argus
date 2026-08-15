package com.argus.auth.security;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpServiceTest {

    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Sha1VectorAndRejectsReplay() {
        TotpService service = new TotpService(() -> 59);
        assertEquals("287082", service.codeForTesting(RFC_SECRET, 1));
        assertEquals(OptionalLong.of(1), service.verify(RFC_SECRET, "287082", -1));
        assertTrue(service.verify(RFC_SECRET, "287082", 1).isEmpty());
    }

    @Test
    void acceptsOnlySixDigitsWithinOneClockStep() {
        TotpService service = new TotpService(() -> 60);
        String previous = service.codeForTesting(RFC_SECRET, 1);
        String next = service.codeForTesting(RFC_SECRET, 3);
        assertEquals(OptionalLong.of(1), service.verify(RFC_SECRET, previous, -1));
        assertEquals(OptionalLong.of(3), service.verify(RFC_SECRET, next, -1));
        assertTrue(service.verify(RFC_SECRET, "12345", -1).isEmpty());
        assertTrue(service.verify(RFC_SECRET, "abcdef", -1).isEmpty());
    }
}
