package com.argus.tools.screening;

/** Stable internal taxonomy to which authoritative lists and KYT vendors can map. */
public enum RiskCategory {
    SANCTIONS,
    DARKNET_MARKET,
    MIXER,
    SCAM,
    STOLEN_FUNDS,
    TERRORISM_FINANCING,
    CHILD_ABUSE,
    GAMBLING,
    HIGH_RISK_EXCHANGE,
    OTHER
}
