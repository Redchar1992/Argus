package com.argus.orchestrator.dto;

import com.argus.orchestrator.model.AgentStep;
import com.argus.orchestrator.model.Investigation;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * API records for the orchestrator. The view records flatten the persisted
 * Investigation/AgentStep into a stable JSON shape for the frontends.
 */
public final class InvestigationDtos {

    private InvestigationDtos() {
    }

    public record SubmitRequest(
            @NotBlank String address,
            Boolean runSync) {
    }

    public record SubmitResponse(String investigationId, String status) {
    }

    public record StepView(
            int index,
            String phase,
            String thought,
            String toolName,
            Object toolArgs,
            Object observation,
            String note,
            String timestamp,
            Long durationMs) {

        static StepView from(AgentStep s) {
            return new StepView(s.getIndex(), s.getPhase(), s.getThought(), s.getToolName(),
                    s.getToolArgs(), s.getObservation(), s.getNote(),
                    s.getTimestamp() == null ? null : s.getTimestamp().toString(), s.getDurationMs());
        }
    }

    public record InvestigationView(
            String id,
            String subjectAddress,
            String status,
            String llmProvider,
            int maxSteps,
            String decision,
            Integer riskScore,
            String riskBand,
            List<String> riskFactors,
            String summary,
            String error,
            String requestedBy,
            String createdAt,
            String completedAt,
            List<StepView> steps) {

        public static InvestigationView from(Investigation inv) {
            return new InvestigationView(
                    inv.getId(), inv.getSubjectAddress(), inv.getStatus(), inv.getLlmProvider(),
                    inv.getMaxSteps(), inv.getDecision(), inv.getRiskScore(), inv.getRiskBand(),
                    inv.getRiskFactors(), inv.getSummary(), inv.getError(), inv.getRequestedBy(),
                    inv.getCreatedAt() == null ? null : inv.getCreatedAt().toString(),
                    inv.getCompletedAt() == null ? null : inv.getCompletedAt().toString(),
                    inv.getSteps().stream().map(StepView::from).toList());
        }
    }
}
