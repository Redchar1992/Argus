package com.argus.auth.service;

import com.argus.auth.dto.AuthDtos.RegisterRequest;
import com.argus.auth.dto.AuthDtos.AuthenticationResponse;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.UserView;
import com.argus.auth.model.Role;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.JwtService;
import com.argus.auth.security.OidcTokenVerifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OidcTokenVerifier oidcTokenVerifier;
    private final MfaService mfaService;

    public AuthService(UserAccountRepository repository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       OidcTokenVerifier oidcTokenVerifier,
                       MfaService mfaService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.oidcTokenVerifier = oidcTokenVerifier;
        this.mfaService = mfaService;
    }

    /**
     * Self-service registration. Security-critical: the role is NEVER taken from the
     * request — every self-registered account is created at the lowest privilege
     * ({@link Role#ANALYST}). Elevation is a separate, ADMIN-only operation
     * ({@link #assignRole}). This closes the privilege-escalation hole where a caller
     * could register themselves as ADMIN.
     */
    public UserView register(RegisterRequest request) {
        if (repository.existsByUsername(request.username())) {
            throw new ResponseStatusException(CONFLICT, "Username already taken");
        }
        String hash = passwordEncoder.encode(request.password());
        UserAccount saved = repository.save(
                new UserAccount(request.username(), hash, Role.ANALYST));
        return toView(saved);
    }

    /** ADMIN-only: change an existing user's role. Gated by {@code @PreAuthorize} on the controller. */
    public UserView assignRole(String username, Role role) {
        UserAccount user = repository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such user: " + username));
        user.setRole(role);
        return toView(repository.save(user));
    }

    public AuthenticationResponse login(String username, String rawPassword) {
        UserAccount user = repository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
        if (!user.isEnabled() || user.getPasswordHash() == null
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }
        return completePrimaryAuthentication(user);
    }

    /**
     * Verifies the provider token independently from the BFF and maps solely by
     * (issuer, subject). Email is profile data, never an account-linking key.
     */
    public AuthenticationResponse oidcLogin(String idToken, String expectedNonce) {
        OidcTokenVerifier.OidcIdentity identity = oidcTokenVerifier.verify(idToken, expectedNonce);
        UserAccount user = repository
                .findByOidcIssuerAndOidcSubject(identity.issuer(), identity.subject())
                .orElseGet(() -> provisionOidcUser(identity));
        if (!user.isEnabled()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Account disabled");
        }
        return completePrimaryAuthentication(user);
    }

    private UserAccount provisionOidcUser(OidcTokenVerifier.OidcIdentity identity) {
        String username = stableOidcUsername(identity.issuer(), identity.subject());
        try {
            return repository.saveAndFlush(UserAccount.oidc(
                    username, identity.issuer(), identity.subject(), identity.email(), Role.ANALYST));
        } catch (DataIntegrityViolationException race) {
            return repository.findByOidcIssuerAndOidcSubject(identity.issuer(), identity.subject())
                    .orElseThrow(() -> race);
        }
    }

    private static String stableOidcUsername(String issuer, String subject) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((issuer + "\u0000" + subject).getBytes(StandardCharsets.UTF_8));
            return "oidc-" + HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private AuthenticationResponse completePrimaryAuthentication(UserAccount user) {
        if (user.isMfaEnabled()) return mfaService.createChallenge(user);
        String token = jwtService.issue(user);
        return new TokenResponse(token, "Bearer", jwtService.getExpirySeconds(),
                user.getUsername(), user.getRole());
    }

    private UserView toView(UserAccount user) {
        return new UserView(user.getId(), user.getUsername(), user.getRole(), user.isEnabled());
    }
}
