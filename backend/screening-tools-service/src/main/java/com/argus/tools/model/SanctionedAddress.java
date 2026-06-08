package com.argus.tools.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single entry on the (seeded) sanctions / watchlist. In a real system this
 * mirrors OFAC SDN, EU/UN consolidated lists, and chain-analytics flags.
 */
@Entity
@Table(name = "sanctioned_address")
public class SanctionedAddress {

    @Id
    @Column(length = 64)
    private String address;

    @Column(nullable = false)
    private String entity;

    @Column(name = "list_source", nullable = false)
    private String listSource;

    @Column(nullable = false)
    private String program;

    @Column(name = "severity", nullable = false)
    private int severity; // 1..100

    protected SanctionedAddress() {
    }

    public SanctionedAddress(String address, String entity, String listSource, String program, int severity) {
        this.address = address;
        this.entity = entity;
        this.listSource = listSource;
        this.program = program;
        this.severity = severity;
    }

    public String getAddress() {
        return address;
    }

    public String getEntity() {
        return entity;
    }

    public String getListSource() {
        return listSource;
    }

    public String getProgram() {
        return program;
    }

    public int getSeverity() {
        return severity;
    }
}
