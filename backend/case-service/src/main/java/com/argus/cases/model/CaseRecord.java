package com.argus.cases.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The persisted outcome of an investigation (SQL side). The full step-by-step
 * reasoning trace lives in the orchestrator's NoSQL store; here we keep the
 * durable, queryable case header + final decision for audit and reporting.
 */
@Entity
@Table(name = "case_record")
public class CaseRecord {

    @Id
    @Column(length = 64)
    private String id; // == investigation id from the orchestrator

    @Column(name = "subject_address", nullable = false, length = 64)
    private String subjectAddress;

    @Column(name = "decision", nullable = false)
    private String decision; // CLEAR | REVIEW | BLOCK

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "risk_band", nullable = false)
    private String riskBand;

    @Lob
    @Column(name = "summary", nullable = false, length = 4000)
    private String summary;

    @Lob
    @Column(name = "risk_factors_json", length = 8000)
    private String riskFactorsJson;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CaseRecord() {
    }

    public CaseRecord(String id, String subjectAddress, String decision, int riskScore,
                      String riskBand, String summary, String riskFactorsJson, String createdBy) {
        this.id = id;
        this.subjectAddress = subjectAddress;
        this.decision = decision;
        this.riskScore = riskScore;
        this.riskBand = riskBand;
        this.summary = summary;
        this.riskFactorsJson = riskFactorsJson;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getSubjectAddress() {
        return subjectAddress;
    }

    public String getDecision() {
        return decision;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public String getRiskBand() {
        return riskBand;
    }

    public String getSummary() {
        return summary;
    }

    public String getRiskFactorsJson() {
        return riskFactorsJson;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
