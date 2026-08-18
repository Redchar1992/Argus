package com.argus.cases.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request/response records for the case-service API.
 */
public final class CaseDtos {

    private CaseDtos() {
    }

    /** Posted by the orchestrator when an investigation finishes. */
    public record PersistCaseRequest(
            @NotBlank String id,
            @NotBlank String subjectAddress,
            @NotBlank String decision,
            int riskScore,
            String riskBand,
            String summary,
            String riskFactorsJson) {
    }

    public record CaseView(
            String id,
            String subjectAddress,
            String decision,
            int riskScore,
            String riskBand,
            String summary,
            String riskFactorsJson,
            String createdBy,
            String createdAt) {
    }

    public record AuditView(
            Long id,
            String actor,
            String action,
            String target,
            String detail,
            String createdAt) {
    }

    public record PolicyView(
            String key,
            String description,
            int value) {
    }

    public record UpdatePolicyRequest(int value, String actor) {
    }
}
