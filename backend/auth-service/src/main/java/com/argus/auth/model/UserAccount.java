package com.argus.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "user_account", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_oidc_identity", columnNames = {"oidc_issuer", "oidc_subject"})
})
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** Bcrypt hash. Never the plaintext password. */
    @Column(name = "password_hash")
    private String passwordHash;

    /** External identities are keyed only by the provider pair, never by email. */
    @Column(name = "oidc_issuer", length = 512)
    private String oidcIssuer;

    @Column(name = "oidc_subject", length = 512)
    private String oidcSubject;

    @Column(length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected UserAccount() {
    }

    public UserAccount(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    public static UserAccount oidc(String username, String issuer, String subject, String email, Role role) {
        UserAccount account = new UserAccount();
        account.username = username;
        account.oidcIssuer = issuer;
        account.oidcSubject = subject;
        account.email = email;
        account.role = role;
        account.enabled = true;
        account.createdAt = Instant.now();
        return account;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getOidcIssuer() {
        return oidcIssuer;
    }

    public String getOidcSubject() {
        return oidcSubject;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
