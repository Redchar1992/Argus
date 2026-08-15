package com.argus.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails production startup unless auth-service requires an authenticated TLS client. */
@Component
public class ProductionTransportSecurity {

    public ProductionTransportSecurity(
            @Value("${server.ssl.enabled:false}") boolean tlsEnabled,
            @Value("${server.ssl.client-auth:none}") String clientAuth,
            @Value("${server.ssl.key-store:}") String keyStore,
            @Value("${server.ssl.trust-store:}") String trustStore,
            Environment environment) {
        if (!isProduction(environment)) return;
        if (!tlsEnabled || !"need".equalsIgnoreCase(clientAuth)) {
            throw new IllegalStateException("Production auth-service must require mutual TLS");
        }
        if (keyStore.isBlank() || trustStore.isBlank()) {
            throw new IllegalStateException("Production auth-service TLS key and trust stores are required");
        }
    }

    private static boolean isProduction(Environment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) return true;
        }
        return false;
    }
}
