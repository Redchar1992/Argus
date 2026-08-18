package com.argus.tools.screening;

/** A normalized provider signal suitable for policy rules and audit display. */
public record RiskSignal(
        RiskCategory category,
        Exposure exposure,
        int hopsAway,
        int severity,
        String entity,
        String detail) {
}
