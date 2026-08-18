package com.argus.tools.screening.ofac;

import com.argus.tools.model.OfacSdnAddress;
import com.argus.tools.model.ScreeningDataset;
import com.argus.tools.repository.OfacSdnAddressRepository;
import com.argus.tools.repository.ScreeningDatasetRepository;
import com.argus.tools.screening.AddressNormalizer;
import com.argus.tools.screening.Exposure;
import com.argus.tools.screening.RiskBand;
import com.argus.tools.screening.RiskCategory;
import com.argus.tools.screening.RiskSignal;
import com.argus.tools.screening.ScreeningMatch;
import com.argus.tools.screening.ScreeningProvider;
import com.argus.tools.screening.ScreeningResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Direct lookup over the last atomically accepted official SDN digital-address dataset. */
@Component
public class OfacSdnProvider implements ScreeningProvider {

    public static final String ID = "ofac";

    private final OfacSdnAddressRepository addresses;
    private final ScreeningDatasetRepository datasets;
    private final OfacProperties properties;

    public OfacSdnProvider(OfacSdnAddressRepository addresses,
                           ScreeningDatasetRepository datasets,
                           OfacProperties properties) {
        this.addresses = addresses;
        this.datasets = datasets;
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean required() {
        return properties.required();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScreeningResult screen(String address) {
        String normalized = AddressNormalizer.normalize(address);
        ScreeningDataset dataset = datasets.findById(ID).orElse(null);
        if (dataset == null) {
            return ScreeningResult.incomplete(normalized, ID, required(), "dataset_missing");
        }
        if (dataset.getFetchedAt().isBefore(Instant.now().minus(properties.maxAge()))) {
            return new ScreeningResult(normalized, ID, required(), false, 0, RiskBand.LOW,
                    List.of(), List.of(), false, dataset.getDatasetVersion(), "dataset_stale");
        }
        List<OfacSdnAddress> found = addresses.findByNormalizedAddress(normalized);
        List<ScreeningMatch> matches = found.stream().map(entry -> new ScreeningMatch(
                entry.getDisplayAddress(), entry.getEntity(), "OFAC-SDN", entry.getProgram(), 100)).toList();
        List<RiskSignal> signals = found.stream().map(entry -> new RiskSignal(
                RiskCategory.SANCTIONS, Exposure.DIRECT, 0, 100, entry.getEntity(),
                "OFAC-SDN " + entry.getAsset() + " profile=" + entry.getProfileId()
                        + " program=" + entry.getProgram())).toList();
        boolean sanctioned = !found.isEmpty();
        return new ScreeningResult(normalized, ID, required(), sanctioned,
                sanctioned ? 100 : 0, sanctioned ? RiskBand.SEVERE : RiskBand.LOW,
                matches, signals, true, dataset.getDatasetVersion(), null);
    }

    public boolean hasUsableDataset() {
        return datasets.findById(ID)
                .map(dataset -> !dataset.getFetchedAt().isBefore(Instant.now().minus(properties.maxAge())))
                .orElse(false);
    }

    public DatasetStatus status() {
        return datasets.findById(ID)
                .map(dataset -> new DatasetStatus(ID, properties.required(), properties.enabled(),
                        dataset.getDatasetVersion(), dataset.getSourceUri(), dataset.getPublishedOn(),
                        dataset.getFetchedAt(), dataset.getEntryCount(),
                        !dataset.getFetchedAt().isBefore(Instant.now().minus(properties.maxAge()))))
                .orElseGet(() -> new DatasetStatus(ID, properties.required(), properties.enabled(),
                        "unavailable", properties.sourceUri(), null, null, 0, false));
    }

    public record DatasetStatus(String providerId, boolean required, boolean enabled,
                                String datasetVersion, String sourceUri, LocalDate publishedOn,
                                Instant fetchedAt, int addressCount, boolean fresh) {
    }
}
