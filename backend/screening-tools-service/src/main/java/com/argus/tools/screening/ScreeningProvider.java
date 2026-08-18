package com.argus.tools.screening;

/** Swappable authoritative-list or KYT provider contract. */
public interface ScreeningProvider {
    String id();

    boolean required();

    ScreeningResult screen(String address);
}
