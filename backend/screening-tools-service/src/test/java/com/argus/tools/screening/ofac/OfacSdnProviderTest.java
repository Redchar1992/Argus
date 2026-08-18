package com.argus.tools.screening.ofac;

import com.argus.tools.model.OfacSdnAddress;
import com.argus.tools.model.ScreeningDataset;
import com.argus.tools.repository.OfacSdnAddressRepository;
import com.argus.tools.repository.ScreeningDatasetRepository;
import com.argus.tools.screening.ScreeningResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfacSdnProviderTest {

    private final OfacSdnAddressRepository addresses = mock(OfacSdnAddressRepository.class);
    private final ScreeningDatasetRepository datasets = mock(ScreeningDatasetRepository.class);
    private final OfacProperties properties = mock(OfacProperties.class);
    private final OfacSdnProvider provider;

    OfacSdnProviderTest() {
        when(properties.required()).thenReturn(true);
        when(properties.enabled()).thenReturn(true);
        when(properties.maxAge()).thenReturn(Duration.ofHours(48));
        when(properties.sourceUri()).thenReturn("official");
        provider = new OfacSdnProvider(addresses, datasets, properties);
    }

    @Test
    void missingOrStaleDatasetCannotProduceCleanEvidence() {
        when(datasets.findById(OfacSdnProvider.ID)).thenReturn(Optional.empty());
        ScreeningResult missing = provider.screen("0xabc");
        assertFalse(missing.evidenceComplete());
        assertEquals("dataset_missing", missing.error());

        when(datasets.findById(OfacSdnProvider.ID)).thenReturn(Optional.of(dataset(
                Instant.now().minus(Duration.ofHours(49)))));
        ScreeningResult stale = provider.screen("0xabc");
        assertFalse(stale.evidenceComplete());
        assertEquals("dataset_stale", stale.error());
    }

    @Test
    void freshDirectMatchProducesSevereAuthoritativeSignal() {
        when(datasets.findById(OfacSdnProvider.ID)).thenReturn(Optional.of(dataset(Instant.now())));
        when(addresses.findByNormalizedAddress("0xabc")).thenReturn(List.of(new OfacSdnAddress(
                "0xabc", "0xAbC", "ETH", "Listed entity", "CYBER2", "42", "v1")));

        ScreeningResult result = provider.screen("0xAbC");
        assertTrue(result.evidenceComplete());
        assertTrue(result.sanctioned());
        assertEquals(100, result.riskScore());
        assertEquals("OFAC-SDN", result.matches().get(0).listSource());
    }

    @Test
    void statusReflectsConfigurationEvenWhenRetainedDataExists() {
        when(properties.enabled()).thenReturn(false);
        when(datasets.findById(OfacSdnProvider.ID)).thenReturn(Optional.of(dataset(Instant.now())));

        assertFalse(provider.status().enabled());
    }

    private static ScreeningDataset dataset(Instant fetchedAt) {
        return new ScreeningDataset(OfacSdnProvider.ID, "official", LocalDate.of(2026, 8, 7),
                fetchedAt, "a".repeat(64), 700, "v1");
    }
}
