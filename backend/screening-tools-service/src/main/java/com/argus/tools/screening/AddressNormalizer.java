package com.argus.tools.screening;

import java.util.Locale;

/** Preserves case-sensitive Base58 while canonicalizing EVM and Bech32 forms. */
public final class AddressNormalizer {

    private AddressNormalizer() {
    }

    public static String normalize(String address) {
        if (address == null) return "";
        String value = address.trim();
        if (value.regionMatches(true, 0, "0x", 0, 2)
                || value.regionMatches(true, 0, "bc1", 0, 3)
                || value.regionMatches(true, 0, "ltc1", 0, 4)) {
            return value.toLowerCase(Locale.ROOT);
        }
        return value;
    }
}
