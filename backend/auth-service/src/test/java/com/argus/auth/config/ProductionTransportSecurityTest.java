package com.argus.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionTransportSecurityTest {

    @Test
    void productionRequiresMutualTlsAndBothStores() {
        MockEnvironment production = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class,
                () -> new ProductionTransportSecurity(false, "none", "", "", production));
        assertThrows(IllegalStateException.class,
                () -> new ProductionTransportSecurity(true, "need", "auth.p12", "", production));
        assertDoesNotThrow(
                () -> new ProductionTransportSecurity(true, "need", "auth.p12", "trust.p12", production));
    }

    @Test
    void developmentAllowsPlainHttpForZeroInfrastructureStartup() {
        assertDoesNotThrow(
                () -> new ProductionTransportSecurity(false, "none", "", "", new MockEnvironment()));
    }
}
