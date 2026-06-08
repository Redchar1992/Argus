package com.argus.cases.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A tunable screening policy threshold. The admin console edits these; the
 * orchestrator can read them to map a final risk score onto a decision.
 */
@Entity
@Table(name = "screening_policy")
public class ScreeningPolicy {

    @Id
    @Column(name = "policy_key", length = 64)
    private String key;

    @Column(nullable = false)
    private String description;

    @Column(name = "int_value", nullable = false)
    private int value;

    protected ScreeningPolicy() {
    }

    public ScreeningPolicy(String key, String description, int value) {
        this.key = key;
        this.description = description;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }
}
