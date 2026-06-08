package com.argus.tools.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A directed transfer in the seeded transaction graph: {@code fromAddress -> toAddress}
 * moving {@code amountUsd}. {@code trace_transactions} walks these edges N hops to
 * surface a wallet's exposure to flagged counterparties.
 */
@Entity
@Table(name = "transaction_edge", indexes = {
        @Index(name = "idx_edge_from", columnList = "from_address"),
        @Index(name = "idx_edge_to", columnList = "to_address")
})
public class TransactionEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_address", nullable = false, length = 64)
    private String fromAddress;

    @Column(name = "to_address", nullable = false, length = 64)
    private String toAddress;

    @Column(name = "amount_usd", nullable = false)
    private double amountUsd;

    @Column(name = "asset", nullable = false)
    private String asset;

    @Column(name = "tx_hash", nullable = false, length = 80)
    private String txHash;

    protected TransactionEdge() {
    }

    public TransactionEdge(String fromAddress, String toAddress, double amountUsd, String asset, String txHash) {
        this.fromAddress = fromAddress;
        this.toAddress = toAddress;
        this.amountUsd = amountUsd;
        this.asset = asset;
        this.txHash = txHash;
    }

    public Long getId() {
        return id;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public String getToAddress() {
        return toAddress;
    }

    public double getAmountUsd() {
        return amountUsd;
    }

    public String getAsset() {
        return asset;
    }

    public String getTxHash() {
        return txHash;
    }
}
