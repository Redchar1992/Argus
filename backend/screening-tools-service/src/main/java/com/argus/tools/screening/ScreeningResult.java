package com.argus.tools.screening;

import java.util.List;

/** One provider's normalized verdict for one address. */
public record ScreeningResult(
        String address,
        String providerId,
        boolean required,
        boolean sanctioned,
        int riskScore,
        RiskBand riskBand,
        List<ScreeningMatch> matches,
        List<RiskSignal> signals,
        boolean evidenceComplete,
        String datasetVersion,
        String error) {

    public ScreeningResult {
        matches = matches == null ? List.of() : List.copyOf(matches);
        signals = signals == null ? List.of() : List.copyOf(signals);
        riskBand = riskBand == null ? RiskBand.LOW : riskBand;
        riskScore = Math.max(0, Math.min(100, riskScore));
    }

    public static ScreeningResult incomplete(String address, String providerId,
                                             boolean required, String error) {
        return new ScreeningResult(address, providerId, required, false, 0, RiskBand.LOW,
                List.of(), List.of(), false, "unavailable", error);
    }
}
