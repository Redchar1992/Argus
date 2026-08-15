package com.argus.auth.controller;

import com.argus.auth.dto.AuthDtos.AssignRoleRequest;
import com.argus.auth.dto.AuthDtos.AuthenticationResponse;
import com.argus.auth.dto.AuthDtos.LoginRequest;
import com.argus.auth.dto.AuthDtos.MfaStatusResponse;
import com.argus.auth.dto.AuthDtos.MfaEnrollmentResponse;
import com.argus.auth.dto.AuthDtos.MfaVerifyRequest;
import com.argus.auth.dto.AuthDtos.OidcLoginRequest;
import com.argus.auth.dto.AuthDtos.RegisterRequest;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.TotpCodeRequest;
import com.argus.auth.dto.AuthDtos.TotpSetupResponse;
import com.argus.auth.dto.AuthDtos.RecoveryCodesResponse;
import com.argus.auth.dto.AuthDtos.RecoveryCompleteRequest;
import com.argus.auth.dto.AuthDtos.RecoveryCompleteResponse;
import com.argus.auth.dto.AuthDtos.RecoveryStatusResponse;
import com.argus.auth.dto.AuthDtos.PasskeyAuthenticationCompleteRequest;
import com.argus.auth.dto.AuthDtos.PasskeyMaterialResponse;
import com.argus.auth.dto.AuthDtos.PasskeyRegistrationContextResponse;
import com.argus.auth.dto.AuthDtos.PasskeyRegistrationRequest;
import com.argus.auth.dto.AuthDtos.PasskeyView;
import com.argus.auth.dto.AuthDtos.IdentityKeyRotationResponse;
import com.argus.auth.dto.AuthDtos.UserView;
import com.argus.auth.security.JwtService;
import com.argus.auth.service.AuthService;
import com.argus.auth.service.MfaService;
import com.argus.auth.service.RecoveryService;
import com.argus.auth.service.PasskeyService;
import com.argus.auth.security.InternalBffAuth;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final MfaService mfaService;
    private final RecoveryService recoveryService;
    private final PasskeyService passkeyService;
    private final InternalBffAuth internalBffAuth;

    public AuthController(AuthService authService, JwtService jwtService, MfaService mfaService,
                          RecoveryService recoveryService, PasskeyService passkeyService,
                          InternalBffAuth internalBffAuth) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.mfaService = mfaService;
        this.recoveryService = recoveryService;
        this.passkeyService = passkeyService;
        this.internalBffAuth = internalBffAuth;
    }

    @PostMapping("/login")
    public AuthenticationResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/oidc/login")
    public AuthenticationResponse oidcLogin(@Valid @RequestBody OidcLoginRequest request) {
        return authService.oidcLogin(request.idToken(), request.nonce());
    }

    @PostMapping("/mfa/verify")
    public TokenResponse verifyMfa(@Valid @RequestBody MfaVerifyRequest request) {
        return mfaService.verifyChallenge(request.challengeToken(), request.method(), request.code());
    }

    @GetMapping("/mfa")
    public MfaStatusResponse mfaStatus(Authentication authentication) {
        return mfaService.status(authentication.getName());
    }

    @PostMapping("/mfa/totp/setup")
    public TotpSetupResponse setupTotp(Authentication authentication) {
        return mfaService.setupTotp(authentication.getName());
    }

    @PostMapping("/mfa/totp/confirm")
    public MfaEnrollmentResponse confirmTotp(Authentication authentication,
                                             @Valid @RequestBody TotpCodeRequest request) {
        return mfaService.confirmTotp(authentication.getName(), request.code());
    }

    @PostMapping("/mfa/totp/disable")
    public MfaStatusResponse disableTotp(Authentication authentication,
                                         @Valid @RequestBody TotpCodeRequest request) {
        return mfaService.disableTotp(authentication.getName(), request.code());
    }

    @GetMapping("/recovery")
    public RecoveryStatusResponse recoveryStatus(Authentication authentication) {
        return mfaService.recoveryStatus(authentication.getName());
    }

    @PostMapping("/recovery/codes/regenerate")
    public RecoveryCodesResponse regenerateRecoveryCodes(Authentication authentication,
                                                          @Valid @RequestBody TotpCodeRequest request) {
        return mfaService.regenerateRecoveryCodes(authentication.getName(), request.code());
    }

    @PostMapping("/recovery/complete")
    public RecoveryCompleteResponse recoverAccount(@Valid @RequestBody RecoveryCompleteRequest request) {
        return recoveryService.complete(request.username(), request.recoveryCode(), request.newPassword());
    }

    @GetMapping("/passkeys")
    public List<PasskeyView> passkeys(Authentication authentication) {
        return passkeyService.list(authentication.getName());
    }

    @GetMapping("/passkeys/context")
    public PasskeyRegistrationContextResponse passkeyRegistrationContext(
            Authentication authentication,
            @RequestHeader(value = "X-Argus-Bff-Secret", required = false) String internalSecret) {
        internalBffAuth.require(internalSecret);
        return passkeyService.registrationContext(authentication.getName());
    }

    @PostMapping("/passkeys")
    @ResponseStatus(HttpStatus.CREATED)
    public PasskeyView registerPasskey(
            Authentication authentication,
            @RequestHeader(value = "X-Argus-Bff-Secret", required = false) String internalSecret,
            @Valid @RequestBody PasskeyRegistrationRequest request) {
        internalBffAuth.require(internalSecret);
        return passkeyService.register(authentication.getName(), request);
    }

    @DeleteMapping("/passkeys/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePasskey(Authentication authentication, @PathVariable String credentialId) {
        passkeyService.delete(authentication.getName(), credentialId);
    }

    @GetMapping("/internal/passkeys/{credentialId}")
    public PasskeyMaterialResponse passkeyMaterial(
            @PathVariable String credentialId,
            @RequestHeader(value = "X-Argus-Bff-Secret", required = false) String internalSecret) {
        internalBffAuth.require(internalSecret);
        return passkeyService.material(credentialId);
    }

    @PostMapping("/internal/passkeys/complete")
    public TokenResponse completePasskeyAuthentication(
            @RequestHeader(value = "X-Argus-Bff-Secret", required = false) String internalSecret,
            @Valid @RequestBody PasskeyAuthenticationCompleteRequest request) {
        internalBffAuth.require(internalSecret);
        return passkeyService.completeAuthentication(request);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    /**
     * ADMIN-only: assign a role to an existing user. This is the ONLY way to grant
     * elevated privileges — self-registration always lands at ANALYST.
     */
    @PutMapping("/users/{username}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public UserView assignRole(@PathVariable String username,
                               @Valid @RequestBody AssignRoleRequest request) {
        return authService.assignRole(username, request.role());
    }

    /** Admin-only bounded drain of TOTP envelopes written by retained encryption keys. */
    @PostMapping("/admin/identity-keys/rotate")
    @PreAuthorize("hasRole('ADMIN')")
    public IdentityKeyRotationResponse rotateIdentityKeys(
            @RequestParam(defaultValue = "100") int limit) {
        return mfaService.rotateIdentitySecrets(limit);
    }

    /** Returns the caller's identity decoded from a valid token. Any authenticated role. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader("Authorization") String authorization) {
        Claims claims = jwtService.parse(authorization.substring(7));
        return ResponseEntity.ok(Map.of(
                "username", claims.getSubject(),
                "role", claims.get("role", String.class)));
    }

    /** Admin-only smoke endpoint proving RBAC is enforced, not faked. */
    @GetMapping("/admin/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, String> adminPing() {
        return Map.of("status", "ok", "scope", "admin");
    }
}
