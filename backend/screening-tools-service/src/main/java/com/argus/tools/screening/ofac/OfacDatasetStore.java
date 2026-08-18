package com.argus.tools.screening.ofac;

import com.argus.tools.model.OfacSdnAddress;
import com.argus.tools.model.ScreeningDataset;
import com.argus.tools.repository.OfacSdnAddressRepository;
import com.argus.tools.repository.ScreeningDatasetRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Replaces entries and metadata in one transaction, so readers see old or new—not half a feed. */
@Component
public class OfacDatasetStore {

    private final OfacSdnAddressRepository addresses;
    private final ScreeningDatasetRepository datasets;

    public OfacDatasetStore(OfacSdnAddressRepository addresses,
                            ScreeningDatasetRepository datasets) {
        this.addresses = addresses;
        this.datasets = datasets;
    }

    @Transactional
    public void replace(OfacSdnParser.ParsedDataset parsed, String sourceUri, Instant fetchedAt,
                        String sha256, String version) {
        addresses.deleteAllInBatch();
        List<OfacSdnAddress> entities = parsed.addresses().stream()
                .map(value -> new OfacSdnAddress(value.normalizedAddress(), value.displayAddress(),
                        value.asset(), limit(value.entity(), 500), limit(value.program(), 500),
                        value.profileId(), version))
                .toList();
        addresses.saveAll(entities);
        addresses.flush();
        datasets.save(new ScreeningDataset(OfacSdnProvider.ID, sourceUri, parsed.publishedOn(),
                fetchedAt, sha256, entities.size(), version));
    }

    private static String limit(String value, int maximum) {
        if (value == null || value.isBlank()) return "UNKNOWN";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
