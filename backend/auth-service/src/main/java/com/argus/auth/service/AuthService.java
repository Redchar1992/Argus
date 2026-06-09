package com.argus.auth.service;

import com.argus.auth.dto.AuthDtos.RegisterRequest;
import com.argus.auth.dto.AuthDtos.TokenResponse;
import com.argus.auth.dto.AuthDtos.UserView;
import com.argus.auth.model.Role;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class AuthService {

    private final UserAccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserAccountRepository repository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /**
     * Self-service registration. Security-critical: the role is NEVER taken from the
     * request — every self-registered account is created at the lowest privilege
     * ({@link Role#ANALYST}). Elevation is a separate, ADMIN-only operation
     * ({@link #assignRole}). This closes the privilege-escalation hole where a caller
     * could register themselves as ADMIN.
     */
    public UserView register(RegisterRequest request) {
        if (repository.existsByUsername(request.username())) {
            throw new ResponseStatusException(CONFLICT, "Username already taken");
        }
        String hash = passwordEncoder.encode(request.password());
        UserAccount saved = repository.save(
                new UserAccount(request.username(), hash, Role.ANALYST));
        return toView(saved);
    }

    /** ADMIN-only: change an existing user's role. Gated by {@code @PreAuthorize} on the controller. */
    public UserView assignRole(String username, Role role) {
        UserAccount user = repository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such user: " + username));
        user.setRole(role);
        return toView(repository.save(user));
    }

    public TokenResponse login(String username, String rawPassword) {
        UserAccount user = repository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "Invalid credentials"));
        if (!user.isEnabled() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(UNAUTHORIZED, "Invalid credentials");
        }
        String token = jwtService.issue(user);
        return new TokenResponse(token, "Bearer", jwtService.getExpirySeconds(),
                user.getUsername(), user.getRole());
    }

    private UserView toView(UserAccount user) {
        return new UserView(user.getId(), user.getUsername(), user.getRole(), user.isEnabled());
    }
}
