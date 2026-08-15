package com.argus.auth.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Keyed hashing prevents usable recovery codes from being recovered from a database dump. */
@Component
public class RecoveryCodeHasher {

    static final String DEV_PEPPER = "YXJndXMtcmVjb3ZlcnktZGV2LXBlcHBlci1rZXktdjE=";
    private final SecretKeySpec key;

    public RecoveryCodeHasher(
            @Value("${argus.recovery.pepper:" + DEV_PEPPER + "}") String configuredPepper,
            Environment environment) {
        byte[] pepper;
        try {
            pepper = Base64.getDecoder().decode(configuredPepper);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("ARGUS_RECOVERY_PEPPER must be valid base64", invalid);
        }
        if (pepper.length < 32) throw new IllegalStateException("ARGUS_RECOVERY_PEPPER must contain at least 32 bytes");
        if (isProduction(environment) && DEV_PEPPER.equals(configuredPepper)) {
            throw new IllegalStateException("The shipped development recovery pepper cannot be used in production");
        }
        this.key = new SecretKeySpec(pepper, "HmacSHA256");
    }

    public String hash(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalize(code).getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    public static String normalize(String code) {
        if (code == null) return "";
        return code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
