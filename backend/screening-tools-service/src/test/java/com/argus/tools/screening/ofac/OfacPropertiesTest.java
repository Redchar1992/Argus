package com.argus.tools.screening.ofac;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfacPropertiesTest {

    @Test
    void productionRejectsOfflineFixtureAndAcceptsOfficialFeed() {
        MockEnvironment production = new MockEnvironment().withProperty("spring.profiles.active", "prod");
        production.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class, () -> properties(
                "classpath:fixtures/ofac-sdn-advanced-excerpt.xml", production));
        assertDoesNotThrow(() -> properties(OfacProperties.OFFICIAL_SOURCE, production));
    }

    @Test
    void productionRejectsLookalikeOfficialUrls() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () -> properties(
                OfacProperties.OFFICIAL_SOURCE + "?mirror=true", production));
        assertThrows(IllegalStateException.class, () -> properties(
                "https://sanctionslistservice.ofac.treas.gov/archive/SDN_ADVANCED.XML", production));
        assertThrows(IllegalStateException.class, () -> properties(
                "https://sanctionslistservice.ofac.treas.gov.evil.example/"
                        + "api/PublicationPreview/exports/SDN_ADVANCED.XML", production));
    }

    private static OfacProperties properties(String source, MockEnvironment environment) {
        return new OfacProperties(List.of("ofac"), source, true, true,
                Duration.ofHours(48), Duration.ofHours(6), Duration.ofSeconds(30),
                200_000_000, 100, "ArgusTest/0.1", environment);
    }
}
