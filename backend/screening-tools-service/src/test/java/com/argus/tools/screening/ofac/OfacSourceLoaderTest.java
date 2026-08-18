package com.argus.tools.screening.ofac;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfacSourceLoaderTest {

    private static final String SHA256 =
            "f4c4a77b065ec4081b789917298ffdf879127245a6a356fee2fcc5b77858d9cd";

    @Test
    void acceptsOfacHexAndStandardBase64DigestFormats() {
        assertEquals(SHA256, OfacSourceLoader.parseSha256Digest("sha-256" + SHA256));
        String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(SHA256));
        assertEquals(SHA256, OfacSourceLoader.parseSha256Digest("sha-256=" + base64));
    }

    @Test
    void rejectsMalformedSha256Digest() {
        assertThrows(IllegalStateException.class,
                () -> OfacSourceLoader.parseSha256Digest("sha-256=not-a-digest"));
    }
}
