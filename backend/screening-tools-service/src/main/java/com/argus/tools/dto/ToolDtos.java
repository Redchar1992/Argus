package com.argus.tools.dto;

import java.util.List;

/**
 * Structured request/response records for the three screening tools. Each tool
 * returns JSON the agent reasons over; shapes are intentionally explicit so the
 * LLM (or local provider) can interpret the observation reliably.
 */
public final class ToolDtos {

    private ToolDtos() {
    }

    // ---- sanctions_screen ----

    public record SanctionsScreenRequest(List<String> addresses) {
    }

    public record SanctionsHit(
            String address,
            String entity,
            String listSource,
            String program,
            int severity) {
    }

    public record SanctionsScreenResponse(
            int addressesChecked,
            int hitCount,
            boolean directHit,
            List<SanctionsHit> hits,
            int riskScore,
            String riskBand,
            boolean evidenceComplete,
            List<ProviderEvidence> providers,
            List<SanctionsRiskSignal> signals) {
    }

    public record ProviderEvidence(
            String address,
            String providerId,
            boolean required,
            boolean evidenceComplete,
            boolean sanctioned,
            int riskScore,
            String riskBand,
            String datasetVersion,
            String error) {
    }

    public record SanctionsRiskSignal(
            String address,
            String providerId,
            String category,
            String exposure,
            int hopsAway,
            int severity,
            String entity,
            String detail) {
    }

    // ---- trace_transactions ----

    public record TraceRequest(
            String address,
            Integer maxHops) {
    }

    public record TracedPathStep(
            String fromAddress,
            String toAddress,
            double amountUsd,
            String asset,
            String txHash,
            int hop) {
    }

    public record FlaggedExposure(
            String flaggedAddress,
            String entity,
            int hopsAway,
            double amountUsdOnPath,
            List<TracedPathStep> path) {
    }

    public record TraceResponse(
            String rootAddress,
            int hopsWalked,
            int edgesVisited,
            int reachableAddresses,
            boolean exposureFound,
            List<FlaggedExposure> flaggedExposures) {
    }

    // ---- address_profile (aggregate stats used to feed risk_rules) ----

    public record AddressProfileRequest(String address) {
    }

    public record AddressProfileResponse(
            String address,
            double totalInflowUsd,
            double totalOutflowUsd,
            int counterpartyCount,
            int txCount) {
    }

    // ---- risk_rules ----

    public record RiskRulesRequest(
            String address,
            Double totalInflowUsd,
            Double totalOutflowUsd,
            Integer counterpartyCount,
            Boolean sanctionsDirectHit,
            Integer minHopsToFlagged) {
    }

    public record FiredRule(
            String ruleId,
            String description,
            int points,
            String evidence) {
    }

    public record RiskRulesResponse(
            String address,
            int riskScore,
            String riskBand,
            List<FiredRule> firedRules) {
    }
}
