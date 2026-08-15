package com.argus.auth;

import com.argus.auth.dto.AuthDtos.RegisterRequest;
import com.argus.auth.dto.AuthDtos.AuthenticationResponse;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.UserView;
import com.argus.auth.model.Role;
import com.argus.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class AuthServiceApplicationTests {

    @Autowired
    private AuthService authService;

    @Test
    void seededAdminCanLoginAndGetsAdminRole() {
        TokenResponse token = token(authService.login("admin", "admin12345"));
        assertNotNull(token.token());
        assertEquals(Role.ADMIN, token.role());
        assertTrue(token.expiresInSeconds() > 0);
        String header = new String(Base64.getUrlDecoder().decode(token.token().split("\\.")[0]),
                StandardCharsets.UTF_8);
        assertTrue(header.contains("\"alg\":\"HS256\""));
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> authService.login("analyst", "not-the-password"));
    }

    @Test
    void registerThenLoginRoundTrips() {
        authService.register(new RegisterRequest("reviewer1", "reviewer-pass"));
        TokenResponse token = token(authService.login("reviewer1", "reviewer-pass"));
        assertEquals("reviewer1", token.username());
        assertEquals(Role.ANALYST, token.role());
    }

    /**
     * Privilege-escalation guard: registration must always produce the lowest privilege,
     * regardless of what a caller tries to slip in. The DTO no longer even carries a role,
     * so the created user is ANALYST, never ADMIN.
     */
    @Test
    void selfRegistrationAlwaysLandsAtAnalystNeverAdmin() {
        UserView created = authService.register(new RegisterRequest("escalator", "hunter2hunter2"));
        assertEquals(Role.ANALYST, created.role());

        TokenResponse token = token(authService.login("escalator", "hunter2hunter2"));
        assertEquals(Role.ANALYST, token.role());
    }

    /** ADMIN-only role assignment actually elevates an existing user. */
    @Test
    void adminCanAssignRole() {
        authService.register(new RegisterRequest("promoteme", "promoteme-pass"));
        UserView promoted = authService.assignRole("promoteme", Role.ADMIN);
        assertEquals(Role.ADMIN, promoted.role());
    }

    private static TokenResponse token(AuthenticationResponse response) {
        return (TokenResponse) response;
    }
}
