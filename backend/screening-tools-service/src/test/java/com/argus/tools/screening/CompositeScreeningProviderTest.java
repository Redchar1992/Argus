package com.argus.tools.screening;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeScreeningProviderTest {

    @Test
    void authoritativeHitWinsAndWorstScoreIsRetained() {
        ScreeningProvider clean = provider("local", true, result("local", true, false, 20));
        ScreeningMatch ofacMatch = new ScreeningMatch("0xabc", "Listed entity", "OFAC-SDN", "CYBER2", 100);
        ScreeningResult hit = new ScreeningResult("0xabc", "ofac", true, true, 100,
                RiskBand.SEVERE, List.of(ofacMatch), List.of(), true, "v1", null);
        CompositeScreeningProvider composite = new CompositeScreeningProvider(
                List.of(clean, provider("ofac", true, hit)), List.of("local", "ofac"));
        composite.validateConfiguration();

        CompositeScreeningProvider.CompositeResult result = composite.screen("0xAbC");
        assertTrue(result.sanctioned());
        assertEquals(100, result.riskScore());
        assertEquals(RiskBand.SEVERE, result.riskBand());
        assertTrue(result.evidenceComplete());
        assertEquals(1, result.matches().size());
    }

    @Test
    void requiredFailureMarksEvidenceIncompleteWhileOptionalFailureDoesNot() {
        ScreeningProvider requiredFailure = throwing("ofac", true);
        CompositeScreeningProvider requiredComposite = new CompositeScreeningProvider(
                List.of(requiredFailure), List.of("ofac"));
        requiredComposite.validateConfiguration();
        assertFalse(requiredComposite.screen("0xabc").evidenceComplete());

        ScreeningProvider clean = provider("local", true, result("local", true, false, 0));
        ScreeningProvider optionalFailure = throwing("vendor", false);
        CompositeScreeningProvider optionalComposite = new CompositeScreeningProvider(
                List.of(clean, optionalFailure), List.of("local", "vendor"));
        optionalComposite.validateConfiguration();
        CompositeScreeningProvider.CompositeResult result = optionalComposite.screen("0xabc");
        assertTrue(result.evidenceComplete());
        assertFalse(result.providers().get(1).evidenceComplete());

        ScreeningProvider invalidProvenance = provider("ofac", true,
                result("different-provider", true, false, 0));
        CompositeScreeningProvider invalidComposite = new CompositeScreeningProvider(
                List.of(invalidProvenance), List.of("ofac"));
        invalidComposite.validateConfiguration();
        assertFalse(invalidComposite.screen("0xabc").evidenceComplete());
    }

    @Test
    void rejectsDuplicateOrEntirelyOptionalConfigurations() {
        ScreeningProvider required = provider("ofac", true, result("ofac", true, false, 0));
        CompositeScreeningProvider duplicate = new CompositeScreeningProvider(
                List.of(required), List.of("ofac", "ofac"));
        assertThrows(IllegalStateException.class, duplicate::validateConfiguration);

        ScreeningProvider optional = provider("vendor", false, result("vendor", false, false, 0));
        CompositeScreeningProvider failOpen = new CompositeScreeningProvider(
                List.of(optional), List.of("vendor"));
        assertThrows(IllegalStateException.class, failOpen::validateConfiguration);
    }

    private static ScreeningResult result(String id, boolean required, boolean sanctioned, int score) {
        return new ScreeningResult("0xabc", id, required, sanctioned, score,
                RiskBand.fromScore(score), List.of(), List.of(), true, "v1", null);
    }

    private static ScreeningProvider provider(String id, boolean required, ScreeningResult result) {
        return new ScreeningProvider() {
            public String id() { return id; }
            public boolean required() { return required; }
            public ScreeningResult screen(String address) { return result; }
        };
    }

    private static ScreeningProvider throwing(String id, boolean required) {
        return new ScreeningProvider() {
            public String id() { return id; }
            public boolean required() { return required; }
            public ScreeningResult screen(String address) { throw new IllegalStateException("down"); }
        };
    }
}
