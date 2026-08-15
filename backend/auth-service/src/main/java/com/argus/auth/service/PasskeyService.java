package com.argus.auth.service;

import com.argus.auth.dto.AuthDtos.PasskeyAuthenticationCompleteRequest;
import com.argus.auth.dto.AuthDtos.PasskeyMaterialResponse;
import com.argus.auth.dto.AuthDtos.PasskeyRegistrationContextResponse;
import com.argus.auth.dto.AuthDtos.PasskeyRegistrationRequest;
import com.argus.auth.dto.AuthDtos.PasskeyView;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.model.PasskeyCredential;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.PasskeyCredentialRepository;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class PasskeyService {

    private static final Set<String> TRANSPORTS = Set.of(
            "ble", "cable", "hybrid", "internal", "nfc", "smart-card", "usb");
    private static final Set<String> DEVICE_TYPES = Set.of("singleDevice", "multiDevice");

    private final PasskeyCredentialRepository credentials;
    private final UserAccountRepository users;
    private final JwtService jwtService;

    public PasskeyService(PasskeyCredentialRepository credentials,
                          UserAccountRepository users,
                          JwtService jwtService) {
        this.credentials = credentials;
        this.users = users;
        this.jwtService = jwtService;
    }

    @Transactional(readOnly = true)
    public List<PasskeyView> list(String username) {
        return credentials.findAllByUser_UsernameOrderByCreatedAtAsc(username).stream()
                .map(PasskeyService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public PasskeyRegistrationContextResponse registrationContext(String username) {
        UserAccount user = requireUser(username);
        List<PasskeyMaterialResponse> materials = credentials
                .findAllByUser_UsernameOrderByCreatedAtAsc(username).stream()
                .map(PasskeyService::material)
                .toList();
        return new PasskeyRegistrationContextResponse(user.getId(), username, materials);
    }

    @Transactional
    public PasskeyView register(String username, PasskeyRegistrationRequest request) {
        UserAccount user = requireUser(username);
        validateMaterial(request.credentialId(), request.publicKey(), request.counter(),
                request.transports(), request.deviceType());
        String label = request.label() == null || request.label().isBlank()
                ? "Passkey" : request.label().trim();
        PasskeyCredential credential = new PasskeyCredential(
                user,
                request.credentialId(),
                request.publicKey(),
                request.counter(),
                joinTransports(request.transports()),
                request.deviceType(),
                request.backedUp(),
                request.aaguid(),
                label,
                Instant.now());
        try {
            return view(credentials.saveAndFlush(credential));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(CONFLICT, "Passkey is already registered");
        }
    }

    @Transactional
    public void delete(String username, String credentialId) {
        PasskeyCredential credential = credentials.findByCredentialIdAndUser_Username(credentialId, username)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Passkey not found"));
        credentials.delete(credential);
    }

    @Transactional(readOnly = true)
    public PasskeyMaterialResponse material(String credentialId) {
        return credentials.findByCredentialId(credentialId)
                .map(PasskeyService::material)
                .orElseThrow(PasskeyService::invalidPasskey);
    }

    /** Counter compare-and-update is repeated here so two valid assertions cannot race. */
    @Transactional
    public TokenResponse completeAuthentication(PasskeyAuthenticationCompleteRequest request) {
        if (request.expectedCounter() < 0 || request.newCounter() < 0
                || !DEVICE_TYPES.contains(request.deviceType())) throw invalidPasskey();
        PasskeyCredential credential = credentials.findLockedByCredentialId(request.credentialId())
                .orElseThrow(PasskeyService::invalidPasskey);
        if (credential.getCounter() != request.expectedCounter()) throw invalidPasskey();
        if (!(request.expectedCounter() == 0 && request.newCounter() == 0)
                && request.newCounter() <= request.expectedCounter()) throw invalidPasskey();
        UserAccount user = credential.getUser();
        if (!user.isEnabled()) throw invalidPasskey();
        credential.authenticated(request.newCounter(), request.deviceType(), request.backedUp(), Instant.now());
        String token = jwtService.issue(user);
        return new TokenResponse(token, "Bearer", jwtService.getExpirySeconds(),
                user.getUsername(), user.getRole());
    }

    private UserAccount requireUser(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such user"));
    }

    private static void validateMaterial(String credentialId, String publicKey, long counter,
                                         List<String> transports, String deviceType) {
        if (counter < 0 || !credentialId.matches("[A-Za-z0-9_-]{16,2048}")
                || !publicKey.matches("[A-Za-z0-9_-]{32,4096}") || !DEVICE_TYPES.contains(deviceType)) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid passkey material");
        }
        try {
            if (Base64.getUrlDecoder().decode(publicKey).length < 32) throw invalidPasskey();
            Base64.getUrlDecoder().decode(credentialId);
        } catch (IllegalArgumentException invalid) {
            throw invalidPasskey();
        }
        if (transports != null && !TRANSPORTS.containsAll(transports)) throw invalidPasskey();
    }

    private static String joinTransports(List<String> transports) {
        if (transports == null || transports.isEmpty()) return "";
        return String.join(",", transports.stream().distinct().sorted().toList());
    }

    private static List<String> splitTransports(String transports) {
        return transports == null || transports.isBlank() ? List.of() : List.of(transports.split(","));
    }

    private static PasskeyMaterialResponse material(PasskeyCredential credential) {
        return new PasskeyMaterialResponse(
                credential.getCredentialId(), credential.getPublicKey(), credential.getCounter(),
                splitTransports(credential.getTransports()), credential.getUser().getUsername(),
                credential.getDeviceType(), credential.isBackedUp());
    }

    private static PasskeyView view(PasskeyCredential credential) {
        return new PasskeyView(
                credential.getCredentialId(), credential.getLabel(), splitTransports(credential.getTransports()),
                credential.getDeviceType(), credential.isBackedUp(), credential.getCreatedAt(),
                credential.getLastUsedAt());
    }

    private static ResponseStatusException invalidPasskey() {
        return new ResponseStatusException(UNAUTHORIZED, "Invalid passkey assertion");
    }
}
