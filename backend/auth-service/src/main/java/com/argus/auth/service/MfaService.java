package com.argus.auth.service;

import com.argus.auth.dto.AuthDtos.MfaChallengeResponse;
import com.argus.auth.dto.AuthDtos.MfaEnrollmentResponse;
import com.argus.auth.dto.AuthDtos.MfaStatusResponse;
import com.argus.auth.dto.AuthDtos.IdentityKeyRotationResponse;
import com.argus.auth.dto.AuthDtos.RecoveryCodesResponse;
import com.argus.auth.dto.AuthDtos.RecoveryStatusResponse;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.TotpSetupResponse;
import com.argus.auth.model.AuthenticationChallenge;
import com.argus.auth.model.MfaMethod;
import com.argus.auth.model.UserAccount;
import com.argus.auth.observability.IdentityMetrics;
import com.argus.auth.repository.AuthenticationChallengeRepository;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.IdentitySecretCipher;
import com.argus.auth.security.JwtService;
import com.argus.auth.security.TotpService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class MfaService {

    private final UserAccountRepository users;
    private final AuthenticationChallengeRepository challenges;
    private final IdentitySecretCipher cipher;
    private final TotpService totp;
    private final JwtService jwtService;
    private final RecoveryService recoveryService;
    private final IdentityMetrics metrics;
    private final SecureRandom random = new SecureRandom();
    private final long challengeTtlSeconds;
    private final long enrollmentTtlSeconds;
    private final int maximumAttempts;

    public MfaService(UserAccountRepository users,
                      AuthenticationChallengeRepository challenges,
                      IdentitySecretCipher cipher,
                      TotpService totp,
                      JwtService jwtService,
                      RecoveryService recoveryService,
                      IdentityMetrics metrics,
                      @Value("${argus.mfa.challenge-ttl-seconds:300}") long challengeTtlSeconds,
                      @Value("${argus.mfa.enrollment-ttl-seconds:600}") long enrollmentTtlSeconds,
                      @Value("${argus.mfa.maximum-attempts:5}") int maximumAttempts) {
        this.users = users;
        this.challenges = challenges;
        this.cipher = cipher;
        this.totp = totp;
        this.jwtService = jwtService;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.challengeTtlSeconds = challengeTtlSeconds;
        this.enrollmentTtlSeconds = enrollmentTtlSeconds;
        this.maximumAttempts = maximumAttempts;
    }

    @Transactional
    public TotpSetupResponse setupTotp(String username) {
        UserAccount user = requireUser(username);
        String secret = totp.generateSecret();
        Instant expiresAt = Instant.now().plusSeconds(enrollmentTtlSeconds);
        user.beginTotpEnrollment(cipher.encrypt(secret), expiresAt);
        users.save(user);
        String label = urlEncode("Argus:" + username);
        String issuer = urlEncode("Argus");
        String uri = "otpauth://totp/" + label + "?secret=" + secret
                + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30";
        return new TotpSetupResponse(secret, uri, expiresAt);
    }

    @Transactional
    public MfaEnrollmentResponse confirmTotp(String username, String code) {
        UserAccount user = requireUser(username);
        Instant now = Instant.now();
        if (user.getPendingTotpSecretEncrypted() == null || user.getPendingTotpExpiresAt() == null
                || !user.getPendingTotpExpiresAt().isAfter(now)) {
            user.clearPendingTotpEnrollment();
            throw new ResponseStatusException(BAD_REQUEST, "TOTP setup expired; start again");
        }
        String secret = cipher.decrypt(user.getPendingTotpSecretEncrypted());
        OptionalLong counter = totp.verify(secret, code, -1);
        if (counter.isEmpty()) throw new ResponseStatusException(UNAUTHORIZED, "Invalid verification code");
        if (cipher.needsRotation(user.getPendingTotpSecretEncrypted())) {
            user.rotatePendingTotpSecret(cipher.encrypt(secret));
            metrics.recordKeyRotation("lazy");
        }
        user.confirmTotpEnrollment(counter.getAsLong(), now);
        UserAccount saved = users.save(user);
        RecoveryCodesResponse recovery = recoveryService.replaceCodes(saved);
        return new MfaEnrollmentResponse(true, user.getMfaEnrolledAt(), recovery.recoveryCodes());
    }

    @Transactional
    public MfaStatusResponse disableTotp(String username, String code) {
        UserAccount user = requireUser(username);
        if (!user.isMfaEnabled()) throw new ResponseStatusException(BAD_REQUEST, "TOTP is not enabled");
        OptionalLong counter = verifyTotp(user, code);
        if (counter.isEmpty()) throw new ResponseStatusException(UNAUTHORIZED, "Invalid verification code");
        user.disableTotp();
        users.save(user);
        return new MfaStatusResponse(false, null);
    }

    @Transactional(readOnly = true)
    public MfaStatusResponse status(String username) {
        UserAccount user = requireUser(username);
        return new MfaStatusResponse(user.isMfaEnabled(), user.getMfaEnrolledAt());
    }

    @Transactional(readOnly = true)
    public RecoveryStatusResponse recoveryStatus(String username) {
        return recoveryService.status(requireUser(username));
    }

    @Transactional
    public RecoveryCodesResponse regenerateRecoveryCodes(String username, String totpCode) {
        UserAccount user = requireUser(username);
        OptionalLong counter = verifyTotp(user, totpCode);
        if (counter.isEmpty()) throw new ResponseStatusException(UNAUTHORIZED, "Invalid verification code");
        user.recordTotpCounter(counter.getAsLong());
        users.save(user);
        return recoveryService.replaceCodes(user);
    }

    @Transactional
    public MfaChallengeResponse createChallenge(UserAccount user) {
        Instant now = Instant.now();
        challenges.deleteByExpiresAtBefore(now.minusSeconds(60));
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        challenges.save(new AuthenticationChallenge(hash(token), user,
                now.plusSeconds(challengeTtlSeconds), now));
        List<MfaMethod> methods = new ArrayList<>();
        methods.add(MfaMethod.TOTP);
        if (recoveryService.hasAvailableCode(user)) methods.add(MfaMethod.RECOVERY_CODE);
        return new MfaChallengeResponse("mfa_required", token, List.copyOf(methods),
                challengeTtlSeconds, user.getUsername());
    }

    /**
     * Re-encrypts a bounded batch of active and pending TOTP seeds under the primary key.
     * Retired keys must remain configured until this reports zero rotations and all active
     * nodes have completed their deployment overlap window.
     */
    @Transactional
    public IdentityKeyRotationResponse rotateIdentitySecrets(int limit) {
        if (limit < 1 || limit > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be between 1 and 500");
        }
        List<UserAccount> candidates = users
                .findIdentitySecretsNeedingRotation("v1." + cipher.primaryKeyId() + ".%",
                        PageRequest.of(0, limit, Sort.by("id").ascending()));
        int rotated = 0;
        for (UserAccount user : candidates) {
            boolean changed = false;
            String active = user.getTotpSecretEncrypted();
            if (active != null && cipher.needsRotation(active)) {
                user.rotateTotpSecret(cipher.rotate(active));
                changed = true;
            }
            String pending = user.getPendingTotpSecretEncrypted();
            if (pending != null && cipher.needsRotation(pending)) {
                user.rotatePendingTotpSecret(cipher.rotate(pending));
                changed = true;
            }
            if (changed) {
                users.save(user);
                rotated++;
            }
        }
        metrics.recordKeyRotation("batch", rotated);
        return new IdentityKeyRotationResponse(cipher.primaryKeyId(), candidates.size(), rotated);
    }

    /** Wrong-code attempt updates must commit so brute-force counters cannot be rolled back. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public TokenResponse verifyChallenge(String challengeToken, MfaMethod method, String code) {
        AuthenticationChallenge challenge = challenges.findByTokenHash(hash(challengeToken))
                .orElseThrow(MfaService::invalidChallenge);
        Instant now = Instant.now();
        if (challenge.getConsumedAt() != null || !challenge.getExpiresAt().isAfter(now)
                || challenge.getAttempts() >= maximumAttempts) {
            challenge.consume(now);
            throw invalidChallenge();
        }
        UserAccount user = challenge.getUser();
        OptionalLong counter = OptionalLong.empty();
        boolean accepted;
        if (method == MfaMethod.TOTP) {
            counter = verifyTotp(user, code);
            accepted = counter.isPresent();
        } else if (method == MfaMethod.RECOVERY_CODE) {
            accepted = user.isEnabled() && recoveryService.consume(user, code);
        } else {
            accepted = false;
        }
        if (!accepted) {
            challenge.failedAttempt(maximumAttempts, now);
            throw invalidChallenge();
        }
        if (counter.isPresent()) user.recordTotpCounter(counter.getAsLong());
        challenge.consume(now);
        users.save(user);
        String token = jwtService.issue(user);
        return new TokenResponse(token, "Bearer", jwtService.getExpirySeconds(),
                user.getUsername(), user.getRole());
    }

    private OptionalLong verifyTotp(UserAccount user, String code) {
        if (!user.isEnabled() || !user.isMfaEnabled()) return OptionalLong.empty();
        long lastCounter = user.getTotpLastCounter() == null ? -1 : user.getTotpLastCounter();
        String envelope = user.getTotpSecretEncrypted();
        String secret = cipher.decrypt(envelope);
        OptionalLong accepted = totp.verify(secret, code, lastCounter);
        if (accepted.isPresent() && cipher.needsRotation(envelope)) {
            user.rotateTotpSecret(cipher.encrypt(secret));
            metrics.recordKeyRotation("lazy");
        }
        return accepted;
    }

    private UserAccount requireUser(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such user"));
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static ResponseStatusException invalidChallenge() {
        return new ResponseStatusException(UNAUTHORIZED, "Invalid or expired MFA challenge");
    }
}
