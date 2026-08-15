package com.argus.auth.security;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/** RFC 6238 TOTP: SHA-1, six digits, 30-second step and a +/- one-step clock window. */
@Service
public class TotpService {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final int STEP_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier epochSeconds;

    public TotpService() {
        this(() -> Instant.now().getEpochSecond());
    }

    TotpService(LongSupplier epochSeconds) {
        this.epochSeconds = epochSeconds;
    }

    public String generateSecret() {
        byte[] secret = new byte[20];
        random.nextBytes(secret);
        return encodeBase32(secret);
    }

    /** Returns the accepted counter, enforcing that it is newer than the last successful use. */
    public OptionalLong verify(String base32Secret, String code, long lastAcceptedCounter) {
        if (code == null || !code.matches("\\d{6}")) return OptionalLong.empty();
        long current = epochSeconds.getAsLong() / STEP_SECONDS;
        for (long counter = current - 1; counter <= current + 1; counter++) {
            if (counter <= lastAcceptedCounter) continue;
            String candidate = code(decodeBase32(base32Secret), counter);
            if (MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(counter);
            }
        }
        return OptionalLong.empty();
    }

    String codeForTesting(String base32Secret, long counter) {
        return code(decodeBase32(base32Secret), counter);
    }

    private static String code(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA1 is unavailable", impossible);
        }
    }

    private static String encodeBase32(byte[] input) {
        StringBuilder output = new StringBuilder((input.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte value : input) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                output.append(BASE32[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) output.append(BASE32[(buffer << (5 - bits)) & 31]);
        return output.toString();
    }

    private static byte[] decodeBase32(String value) {
        String normalized = value.replace("=", "").toUpperCase(Locale.ROOT);
        byte[] output = new byte[normalized.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char character : normalized.toCharArray()) {
            int decoded = character >= 'A' && character <= 'Z'
                    ? character - 'A'
                    : character >= '2' && character <= '7' ? character - '2' + 26 : -1;
            if (decoded < 0) throw new IllegalArgumentException("Invalid Base32 secret");
            buffer = (buffer << 5) | decoded;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return output;
    }
}
