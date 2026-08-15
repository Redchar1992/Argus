package com.argus.auth.dto;

import com.argus.auth.model.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request/response records for the auth endpoints.
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    /** A provider ID token completed by the BFF's code + PKCE flow. */
    public record OidcLoginRequest(
            @NotBlank @Size(max = 16_384) String idToken,
            @NotBlank @Size(max = 256) String nonce) {
    }

    /**
     * Self-service registration. Intentionally carries NO role field: a caller must
     * never be able to choose their own privilege. New users are always created at the
     * lowest privilege (see {@link com.argus.auth.service.AuthService#register}); an
     * admin elevates them afterwards via the admin-only role-assignment endpoint.
     */
    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    /** Admin-only: assign a role to an existing user. */
    public record AssignRoleRequest(
            @NotNull Role role) {
    }

    public record TokenResponse(
            String token,
            String tokenType,
            long expiresInSeconds,
            String username,
            Role role) {
    }

    public record UserView(
            Long id,
            String username,
            Role role,
            boolean enabled) {
    }
}
