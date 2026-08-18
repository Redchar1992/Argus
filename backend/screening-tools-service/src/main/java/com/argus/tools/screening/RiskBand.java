package com.argus.tools.screening;

/** Provider-neutral severity band ordered from least to most restrictive. */
public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH,
    SEVERE;

    public static RiskBand fromScore(int score) {
        if (score >= 90) return SEVERE;
        if (score >= 60) return HIGH;
        if (score >= 30) return MEDIUM;
        return LOW;
    }

    public static RiskBand worst(RiskBand left, RiskBand right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
