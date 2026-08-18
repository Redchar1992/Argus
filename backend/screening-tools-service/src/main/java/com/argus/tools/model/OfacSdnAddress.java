package com.argus.tools.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "ofac_sdn_address", uniqueConstraints = @UniqueConstraint(
        name = "uk_ofac_asset_address_profile",
        columnNames = {"asset", "normalized_address", "profile_id"}))
public class OfacSdnAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "normalized_address", nullable = false, length = 256)
    private String normalizedAddress;

    @Column(name = "display_address", nullable = false, length = 256)
    private String displayAddress;

    @Column(nullable = false, length = 24)
    private String asset;

    @Column(nullable = false, length = 500)
    private String entity;

    @Column(nullable = false, length = 500)
    private String program;

    @Column(name = "profile_id", nullable = false, length = 64)
    private String profileId;

    @Column(name = "dataset_version", nullable = false, length = 96)
    private String datasetVersion;

    protected OfacSdnAddress() {
    }

    public OfacSdnAddress(String normalizedAddress, String displayAddress, String asset,
                          String entity, String program, String profileId, String datasetVersion) {
        this.normalizedAddress = normalizedAddress;
        this.displayAddress = displayAddress;
        this.asset = asset;
        this.entity = entity;
        this.program = program;
        this.profileId = profileId;
        this.datasetVersion = datasetVersion;
    }

    public String getNormalizedAddress() {
        return normalizedAddress;
    }

    public String getDisplayAddress() {
        return displayAddress;
    }

    public String getAsset() {
        return asset;
    }

    public String getEntity() {
        return entity;
    }

    public String getProgram() {
        return program;
    }

    public String getProfileId() {
        return profileId;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }
}
