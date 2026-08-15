package com.argus.auth.controller;

import com.argus.auth.dto.AuthDtos.AssignRoleRequest;
import com.argus.auth.dto.AuthDtos.AuthenticationResponse;
import com.argus.auth.dto.AuthDtos.LoginRequest;
import com.argus.auth.dto.AuthDtos.MfaStatusResponse;
import com.argus.auth.dto.AuthDtos.MfaVerifyRequest;
import com.argus.auth.dto.AuthDtos.OidcLoginRequest;
import com.argus.auth.dto.AuthDtos.RegisterRequest;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.TotpCodeRequest;
import com.argus.auth.dto.AuthDtos.TotpSetupResponse;
import com.argus.auth.dto.AuthDtos.UserView;
import com.argus.auth.security.JwtService;
import com.argus.auth.service.AuthService;
import com.argus.auth.service.MfaService;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final MfaService mfaService;

    public AuthController(AuthService authService, JwtService jwtService, MfaService mfaService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.mfaService = mfaService;
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
    public MfaStatusResponse confirmTotp(Authentication authentication,
                                         @Valid @RequestBody TotpCodeRequest request) {
        return mfaService.confirmTotp(authentication.getName(), request.code());
    }

    @PostMapping("/mfa/totp/disable")
    public MfaStatusResponse disableTotp(Authentication authentication,
                                         @Valid @RequestBody TotpCodeRequest request) {
        return mfaService.disableTotp(authentication.getName(), request.code());
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
