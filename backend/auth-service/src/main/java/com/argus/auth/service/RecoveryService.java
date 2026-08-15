package com.argus.auth.service;

import com.argus.auth.dto.AuthDtos.RecoveryCodesResponse;
import com.argus.auth.dto.AuthDtos.RecoveryCompleteResponse;
import com.argus.auth.dto.AuthDtos.RecoveryStatusResponse;
import com.argus.auth.model.RecoveryCode;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.AuthenticationChallengeRepository;
import com.argus.auth.repository.RecoveryCodeRepository;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.RecoveryCodeHasher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class RecoveryService {

    private static final char[] BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_COUNT = 10;
    private static final int CODE_CHARACTERS = 24;

    private final RecoveryCodeRepository codes;
    private final UserAccountRepository users;
    private final AuthenticationChallengeRepository challenges;
    private final RecoveryCodeHasher hasher;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public RecoveryService(RecoveryCodeRepository codes,
                           UserAccountRepository users,
                           AuthenticationChallengeRepository challenges,
                           RecoveryCodeHasher hasher,
                           PasswordEncoder passwordEncoder) {
        this.codes = codes;
        this.users = users;
        this.challenges = challenges;
        this.hasher = hasher;
        this.passwordEncoder = passwordEncoder;
    }

    /** Replaces every old code and returns plaintext only in this one response. */
    @Transactional
    public RecoveryCodesResponse replaceCodes(UserAccount user) {
        codes.deleteByUser_Id(user.getId());
        Instant now = Instant.now();
        List<String> plaintext = new ArrayList<>(CODE_COUNT);
        for (int index = 0; index < CODE_COUNT; index++) {
            String code = generateCode();
            plaintext.add(format(code));
            codes.save(new RecoveryCode(user, hasher.hash(code), now));
        }
        return new RecoveryCodesResponse(List.copyOf(plaintext), CODE_COUNT, now);
    }

    @Transactional(readOnly = true)
    public RecoveryStatusResponse status(UserAccount user) {
        return new RecoveryStatusResponse(codes.countByUser_IdAndUsedAtIsNull(user.getId()));
    }

    @Transactional(readOnly = true)
    public boolean hasAvailableCode(UserAccount user) {
        return codes.countByUser_IdAndUsedAtIsNull(user.getId()) > 0;
    }

    /** Atomically consumes a code. Callers deliberately receive only a boolean. */
    @Transactional
    public boolean consume(UserAccount user, String plaintextCode) {
        String normalized = RecoveryCodeHasher.normalize(plaintextCode);
        if (!normalized.matches("[A-Z2-9]{24}")) return false;
        return codes.findByUser_IdAndCodeHash(user.getId(), hasher.hash(normalized))
                .filter(code -> !code.isUsed())
                .map(code -> {
                    code.consume(Instant.now());
                    return true;
                })
                .orElse(false);
    }

    /**
     * Offline recovery is intentionally provider-independent: one high-entropy one-time
     * code proves control, resets the password, and invalidates every pending auth challenge.
     */
    @Transactional
    public RecoveryCompleteResponse complete(String username, String recoveryCode, String newPassword) {
        UserAccount user = users.findByUsername(username).orElseThrow(RecoveryService::invalidRecovery);
        if (!user.isEnabled() || !consume(user, recoveryCode)) throw invalidRecovery();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        users.save(user);
        challenges.deleteByUser_Id(user.getId());
        return new RecoveryCompleteResponse("recovered", "Password reset. Sign in with your new password.");
    }

    private String generateCode() {
        StringBuilder value = new StringBuilder(CODE_CHARACTERS);
        for (int index = 0; index < CODE_CHARACTERS; index++) {
            value.append(BASE32[random.nextInt(BASE32.length)]);
        }
        return value.toString();
    }

    private static String format(String value) {
        return String.join("-", value.substring(0, 4), value.substring(4, 8), value.substring(8, 12),
                value.substring(12, 16), value.substring(16, 20), value.substring(20, 24));
    }

    private static ResponseStatusException invalidRecovery() {
        return new ResponseStatusException(UNAUTHORIZED, "Invalid account or recovery code");
    }
}
