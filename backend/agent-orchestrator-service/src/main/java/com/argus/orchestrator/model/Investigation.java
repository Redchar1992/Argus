package com.argus.orchestrator.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The full record of one agentic investigation: the subject wallet, the live
 * status, the ordered step-by-step trace, and (when complete) the final decision.
 *
 * Stored in MongoDB (NoSQL) because the trace is a variable-length, semi-structured
 * document — the natural fit. The durable SQL "case header" is mirrored into
 * case-service on completion.
 */
@Document(collection = "investigations")
public class Investigation {

    @Id
    private String id;

    private String subjectAddress;
    private String status;           // RUNNING | COMPLETED | FAILED
    private String llmProvider;      // which provider drove this run
    private int maxSteps;

    private final List<AgentStep> steps = new ArrayList<>();

    // Final decision (populated on FINISH)
    private String decision;         // CLEAR | REVIEW | BLOCK
    private Integer riskScore;
    private String riskBand;
    private List<String> riskFactors = new ArrayList<>();
    private String summary;
    private String error;

    private String requestedBy;
    private Instant createdAt = Instant.now();
    private Instant completedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSubjectAddress() {
        return subjectAddress;
    }

    public void setSubjectAddress(String subjectAddress) {
        this.subjectAddress = subjectAddress;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public List<AgentStep> getSteps() {
        return steps;
    }

    public void addStep(AgentStep step) {
        this.steps.add(step);
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public Integer getRiskScore() {
        return riskScore;
    }

    public void setRiskScore(Integer riskScore) {
        this.riskScore = riskScore;
    }

    public String getRiskBand() {
        return riskBand;
    }

    public void setRiskBand(String riskBand) {
        this.riskBand = riskBand;
    }

    public List<String> getRiskFactors() {
        return riskFactors;
    }

    public void setRiskFactors(List<String> riskFactors) {
        this.riskFactors = riskFactors;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
