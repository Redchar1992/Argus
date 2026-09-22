package com.argus.cases.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    /** A human intervention on a case that the policy routed to review. */
    public record ReviewCaseRequest(
            @NotBlank String action,
            @Size(max = 4000) String note) {
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
            String createdAt,
            String reviewStatus,
            String reviewDecision,
            String reviewNote,
            String reviewedBy,
            String reviewedAt) {
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
