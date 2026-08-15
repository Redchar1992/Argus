package com.argus.tools.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OAuth2 resource-server security. This service used to run unauthenticated ("裸奔");
 * it now validates the SAME HS256 JWT that auth-service issues, using the shared
 * {@code ARGUS_JWT_SECRET}. The token's {@code role} claim is mapped to a
 * {@code ROLE_*} authority so {@code @PreAuthorize("hasRole(...)")} on the controller
 * enforces RBAC. Unauthenticated requests get 401; authenticated-but-under-privileged
 * requests get 403.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /** The shipped dev default. Signing real tokens with this is a public-key leak. */
    static final String DEFAULT_DEV_SECRET = "argus-dev-secret-change-me-please-32bytes-minimum-length";

    private final String jwtSecret;
    private final Environment environment;

    public SecurityConfig(@Value("${argus.jwt.secret}") String jwtSecret, Environment environment) {
        this.jwtSecret = jwtSecret;
        this.environment = environment;
    }

    /**
     * Guard against silently shipping the public dev signing key. If the JWT secret is still
     * the shipped default, fail fast on a production profile (so a misconfigured deploy can
     * never sign tokens with a key that is in the repo) and log a loud WARN everywhere else
     * (dev / tests, which legitimately use the default).
     */
    @PostConstruct
    void assertJwtSecretConfigured() {
        if (!DEFAULT_DEV_SECRET.equals(jwtSecret)) {
            return;
        }
        boolean production = false;
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                production = true;
                break;
            }
        }
        if (production) {
            throw new IllegalStateException(
                    "ARGUS_JWT_SECRET is still the shipped dev default on a production profile. "
                            + "Set ARGUS_JWT_SECRET to a real secret (>=32 bytes) before deploying.");
        }
        log.warn("ARGUS_JWT_SECRET is using the shipped dev default — this signing key is public. "
                + "Acceptable for dev/test only; set ARGUS_JWT_SECRET before any real deployment.");
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /** Maps the auth-service {@code role} string claim onto a Spring {@code ROLE_*} authority. */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) {
                return List.of();
            }
            List<GrantedAuthority> authorities =
                    List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationConverter converter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
