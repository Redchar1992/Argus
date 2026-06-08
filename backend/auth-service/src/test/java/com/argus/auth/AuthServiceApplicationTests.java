package com.argus.auth;

import com.argus.auth.dto.AuthDtos.RegisterRequest;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.model.Role;
import com.argus.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

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
        TokenResponse token = authService.login("admin", "admin12345");
        assertNotNull(token.token());
        assertEquals(Role.ADMIN, token.role());
        assertTrue(token.expiresInSeconds() > 0);
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> authService.login("analyst", "not-the-password"));
    }

    @Test
    void registerThenLoginRoundTrips() {
        authService.register(new RegisterRequest("reviewer1", "reviewer-pass", Role.ANALYST));
        TokenResponse token = authService.login("reviewer1", "reviewer-pass");
        assertEquals("reviewer1", token.username());
        assertEquals(Role.ANALYST, token.role());
    }
}
