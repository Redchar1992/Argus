package com.argus.auth.dto;

import com.argus.auth.model.Role;
import jakarta.validation.constraints.NotBlank;
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

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            Role role) {
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
