package com.argus.auth.service;

import com.argus.auth.dto.AuthDtos.MfaChallengeResponse;
import com.argus.auth.dto.AuthDtos.MfaStatusResponse;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.TotpSetupResponse;
import com.argus.auth.model.AuthenticationChallenge;
import com.argus.auth.model.MfaMethod;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.AuthenticationChallengeRepository;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.IdentitySecretCipher;
import com.argus.auth.security.JwtService;
import com.argus.auth.security.TotpService;
import org.springframework.beans.factory.annotation.Value;
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
    private final SecureRandom random = new SecureRandom();
    private final long challengeTtlSeconds;
    private final long enrollmentTtlSeconds;
    private final int maximumAttempts;

    public MfaService(UserAccountRepository users,
                      AuthenticationChallengeRepository challenges,
                      IdentitySecretCipher cipher,
                      TotpService totp,
                      JwtService jwtService,
                      @Value("${argus.mfa.challenge-ttl-seconds:300}") long challengeTtlSeconds,
                      @Value("${argus.mfa.enrollment-ttl-seconds:600}") long enrollmentTtlSeconds,
                      @Value("${argus.mfa.maximum-attempts:5}") int maximumAttempts) {
        this.users = users;
        this.challenges = challenges;
        this.cipher = cipher;
        this.totp = totp;
        this.jwtService = jwtService;
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
    public MfaStatusResponse confirmTotp(String username, String code) {
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
        user.confirmTotpEnrollment(counter.getAsLong(), now);
        users.save(user);
        return new MfaStatusResponse(true, user.getMfaEnrolledAt());
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

    @Transactional
    public MfaChallengeResponse createChallenge(UserAccount user) {
        Instant now = Instant.now();
        challenges.deleteByExpiresAtBefore(now.minusSeconds(60));
        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        challenges.save(new AuthenticationChallenge(hash(token), user,
                now.plusSeconds(challengeTtlSeconds), now));
        return new MfaChallengeResponse("mfa_required", token, List.of(MfaMethod.TOTP),
                challengeTtlSeconds, user.getUsername());
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
        if (method != MfaMethod.TOTP) {
            challenge.failedAttempt(maximumAttempts, now);
            throw invalidChallenge();
        }

        UserAccount user = challenge.getUser();
        OptionalLong counter = verifyTotp(user, code);
        if (counter.isEmpty()) {
            challenge.failedAttempt(maximumAttempts, now);
            throw invalidChallenge();
        }
        user.recordTotpCounter(counter.getAsLong());
        challenge.consume(now);
        users.save(user);
        String token = jwtService.issue(user);
        return new TokenResponse(token, "Bearer", jwtService.getExpirySeconds(),
                user.getUsername(), user.getRole());
    }

    private OptionalLong verifyTotp(UserAccount user, String code) {
        if (!user.isEnabled() || !user.isMfaEnabled()) return OptionalLong.empty();
        long lastCounter = user.getTotpLastCounter() == null ? -1 : user.getTotpLastCounter();
        return totp.verify(cipher.decrypt(user.getTotpSecretEncrypted()), code, lastCounter);
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
