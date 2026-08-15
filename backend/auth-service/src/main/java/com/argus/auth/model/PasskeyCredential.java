package com.argus.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "passkey_credential")
public class PasskeyCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @Column(name = "credential_id", nullable = false, unique = true, length = 2048)
    private String credentialId;

    @Column(name = "public_key", nullable = false, length = 4096)
    private String publicKey;

    @Column(nullable = false)
    private long counter;

    @Column(length = 256)
    private String transports;

    @Column(name = "device_type", nullable = false, length = 32)
    private String deviceType;

    @Column(name = "backed_up", nullable = false)
    private boolean backedUp;

    @Column(length = 36)
    private String aaguid;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Version
    private long version;

    protected PasskeyCredential() {
    }

    public PasskeyCredential(UserAccount user, String credentialId, String publicKey, long counter,
                             String transports, String deviceType, boolean backedUp, String aaguid,
                             String label, Instant createdAt) {
        this.user = user;
        this.credentialId = credentialId;
        this.publicKey = publicKey;
        this.counter = counter;
        this.transports = transports;
        this.deviceType = deviceType;
        this.backedUp = backedUp;
        this.aaguid = aaguid;
        this.label = label;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public UserAccount getUser() { return user; }
    public String getCredentialId() { return credentialId; }
    public String getPublicKey() { return publicKey; }
    public long getCounter() { return counter; }
    public String getTransports() { return transports; }
    public String getDeviceType() { return deviceType; }
    public boolean isBackedUp() { return backedUp; }
    public String getAaguid() { return aaguid; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }

    public void authenticated(long newCounter, String newDeviceType, boolean newBackedUp, Instant now) {
        this.counter = newCounter;
        this.deviceType = newDeviceType;
        this.backedUp = newBackedUp;
        this.lastUsedAt = now;
    }
}
