package com.argus.tools.config;

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

/** Separately validates user-admin and orchestrator-workload RS256 trust domains. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final RsaKeyRing authKeys;
    private final RsaKeyRing workloadKeys;
    private final String authIssuer;
    private final String authAudience;
    private final String workloadIssuer;
    private final String workloadAudience;

    public SecurityConfig(
            @Value("${argus.jwt.public-keys:}") String authPublicKeys,
            @Value("${argus.jwt.issuer:urn:argus:auth}") String authIssuer,
            @Value("${argus.jwt.audience:argus-admin-api}") String authAudience,
            @Value("${argus.workload.jwt.public-keys:}") String workloadPublicKeys,
            @Value("${argus.workload.jwt.issuer:urn:argus:workload}") String workloadIssuer,
            @Value("${argus.workload.jwt.audience:argus-screening-tools}") String workloadAudience,
            Environment environment) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod")
                        || profile.equalsIgnoreCase("production"));
        this.authKeys = RsaKeyRing.verification(authPublicKeys, KeyPurpose.AUTH, production);
        this.workloadKeys = RsaKeyRing.verification(workloadPublicKeys, KeyPurpose.WORKLOAD, production);
        this.authIssuer = authIssuer;
        this.authAudience = authAudience;
        this.workloadIssuer = workloadIssuer;
        this.workloadAudience = workloadAudience;
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return JwtSecurity.decoder(
                new JwtSecurity.TrustRoute(authKeys.publicKeys(), JwtSecurity.validator(
                        authIssuer, authAudience, JwtSecurity.USER_TOKEN_TYPE)),
                new JwtSecurity.TrustRoute(workloadKeys.publicKeys(), JwtSecurity.validator(
                        workloadIssuer, workloadAudience, JwtSecurity.WORKLOAD_TOKEN_TYPE)));
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
