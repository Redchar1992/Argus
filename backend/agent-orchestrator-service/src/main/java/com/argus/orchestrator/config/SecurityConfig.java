package com.argus.orchestrator.config;

import com.argus.security.jwt.JwtSecurity;
import com.argus.security.jwt.KeyPurpose;
import com.argus.security.jwt.RsaKeyRing;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.Arrays;
import java.util.List;

/** Validates auth-service RS256 user tokens for the orchestrator audience. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final RsaKeyRing authKeys;
    private final String issuer;
    private final String audience;

    public SecurityConfig(
            @Value("${argus.jwt.public-keys:}") String publicKeys,
            @Value("${argus.jwt.issuer:urn:argus:auth}") String issuer,
            @Value("${argus.jwt.audience:argus-orchestrator}") String audience,
            Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
        this.authKeys = RsaKeyRing.verification(publicKeys, KeyPurpose.AUTH, production);
        this.issuer = issuer;
        this.audience = audience;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return JwtSecurity.decoder(new JwtSecurity.TrustRoute(
                authKeys.publicKeys(),
                JwtSecurity.validator(issuer, audience, JwtSecurity.USER_TOKEN_TYPE)));
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            if (role == null || role.isBlank()) return List.of();
            List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return converter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationConverter converter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> { })
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/.well-known/workload-jwks.json").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
