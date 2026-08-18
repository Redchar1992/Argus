package com.argus.tools.screening.ofac;

import com.argus.tools.model.ScreeningDataset;
import com.argus.tools.repository.ScreeningDatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Refreshes on a bounded startup policy and daily thereafter while retaining last-known-good data. */
@Component
public class OfacSdnBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OfacSdnBootstrap.class);

    private final OfacProperties properties;
    private final OfacSdnIngestionService ingestion;
    private final OfacSdnProvider provider;
    private final ScreeningDatasetRepository datasets;

    public OfacSdnBootstrap(OfacProperties properties, OfacSdnIngestionService ingestion,
                            OfacSdnProvider provider, ScreeningDatasetRepository datasets) {
        this.properties = properties;
        this.ingestion = ingestion;
        this.provider = provider;
        this.datasets = datasets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled() || !properties.refreshOnStartup() || !needsRefresh()) return;
        try {
            logAccepted(ingestion.refresh());
        } catch (RuntimeException failure) {
            if (properties.required() && !provider.hasUsableDataset()) {
                throw new IllegalStateException("Required OFAC dataset is unavailable", failure);
            }
            log.warn("OFAC startup refresh failed; retaining last-known-good dataset: {}",
                    failure.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${argus.screening.ofac.refresh-cron:0 0 3 * * *}", zone = "UTC")
    public void scheduledRefresh() {
        if (!properties.enabled()) return;
        try {
            logAccepted(ingestion.refresh());
        } catch (RuntimeException failure) {
            log.warn("OFAC scheduled refresh failed closed: {}", failure.getClass().getSimpleName());
        }
    }

    public OfacSdnIngestionService.RefreshResult refreshNow() {
        if (!properties.enabled()) throw new IllegalStateException("OFAC provider is disabled");
        OfacSdnIngestionService.RefreshResult result = ingestion.refresh();
        logAccepted(result);
        return result;
    }

    private boolean needsRefresh() {
        ScreeningDataset dataset = datasets.findById(OfacSdnProvider.ID).orElse(null);
        return dataset == null || !dataset.getSourceUri().equals(properties.sourceUri())
                || dataset.getFetchedAt().isBefore(Instant.now().minus(properties.refreshIfOlderThan()));
    }

    private void logAccepted(OfacSdnIngestionService.RefreshResult result) {
        log.info("Accepted OFAC dataset {} with {} digital-currency addresses",
                result.version(), result.addressCount());
    }
}
