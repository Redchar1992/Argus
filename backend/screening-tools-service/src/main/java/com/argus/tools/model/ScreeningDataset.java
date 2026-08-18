package com.argus.tools.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/** Last atomically accepted provider dataset and its content-integrity evidence. */
@Entity
@Table(name = "screening_dataset")
public class ScreeningDataset {

    @Id
    @Column(name = "provider_id", length = 64)
    private String providerId;

    @Column(name = "source_uri", nullable = false, length = 1024)
    private String sourceUri;

    @Column(name = "published_on", nullable = false)
    private LocalDate publishedOn;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "dataset_version", nullable = false, length = 96)
    private String datasetVersion;

    protected ScreeningDataset() {
    }

    public ScreeningDataset(String providerId, String sourceUri, LocalDate publishedOn,
                            Instant fetchedAt, String sha256, int entryCount, String datasetVersion) {
        this.providerId = providerId;
        this.sourceUri = sourceUri;
        this.publishedOn = publishedOn;
        this.fetchedAt = fetchedAt;
        this.sha256 = sha256;
        this.entryCount = entryCount;
        this.datasetVersion = datasetVersion;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public LocalDate getPublishedOn() {
        return publishedOn;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public String getSha256() {
        return sha256;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }
}
