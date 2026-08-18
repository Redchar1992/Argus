package com.argus.tools.screening;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fans out to configured providers and merges with authoritative-hit-wins, fail-closed semantics. */
@Component
public class CompositeScreeningProvider {

    private static final Logger log = LoggerFactory.getLogger(CompositeScreeningProvider.class);

    private final Map<String, ScreeningProvider> available;
    private final List<String> enabledIds;
    private List<ScreeningProvider> enabled;

    public CompositeScreeningProvider(
            List<ScreeningProvider> providers,
            @Value("${argus.screening.providers:local,ofac}") List<String> enabledIds) {
        Map<String, ScreeningProvider> indexed = new LinkedHashMap<>();
        for (ScreeningProvider provider : providers) {
            if (indexed.putIfAbsent(provider.id(), provider) != null) {
                throw new IllegalStateException("Duplicate screening provider ID: " + provider.id());
            }
        }
        this.available = Map.copyOf(indexed);
        this.enabledIds = enabledIds.stream().map(String::trim).filter(id -> !id.isBlank()).toList();
    }

    @PostConstruct
    void validateConfiguration() {
        if (enabledIds.isEmpty()) throw new IllegalStateException("At least one screening provider is required");
        if (new LinkedHashSet<>(enabledIds).size() != enabledIds.size()) {
            throw new IllegalStateException("Duplicate enabled screening provider IDs: " + enabledIds);
        }
        List<String> missing = enabledIds.stream().filter(id -> !available.containsKey(id)).toList();
        if (!missing.isEmpty()) throw new IllegalStateException("Unknown screening providers: " + missing);
        enabled = enabledIds.stream().map(available::get).toList();
        if (enabled.stream().noneMatch(ScreeningProvider::required)) {
            throw new IllegalStateException("At least one enabled screening provider must fail closed");
        }
    }

    public CompositeResult screen(String address) {
        String normalized = AddressNormalizer.normalize(address);
        List<ScreeningResult> results = new ArrayList<>();
        for (ScreeningProvider provider : enabled) {
            try {
                ScreeningResult result = provider.screen(normalized);
                if (result == null || !provider.id().equals(result.providerId())
                        || provider.required() != result.required()) {
                    throw new IllegalStateException("Screening provider returned invalid provenance");
                }
                results.add(result);
            } catch (RuntimeException failure) {
                log.warn("Screening provider {} failed closed: {}", provider.id(),
                        failure.getClass().getSimpleName());
                results.add(ScreeningResult.incomplete(normalized, provider.id(),
                        provider.required(), "provider_error"));
            }
        }

        boolean complete = results.stream().noneMatch(result -> result.required()
                && !result.evidenceComplete());
        boolean sanctioned = results.stream().anyMatch(ScreeningResult::sanctioned);
        int score = results.stream().mapToInt(ScreeningResult::riskScore).max().orElse(0);
        RiskBand band = results.stream().map(ScreeningResult::riskBand)
                .reduce(RiskBand.LOW, RiskBand::worst);
        Set<ScreeningMatch> matches = new LinkedHashSet<>();
        Set<RiskSignal> signals = new LinkedHashSet<>();
        results.forEach(result -> {
            matches.addAll(result.matches());
            signals.addAll(result.signals());
        });
        return new CompositeResult(normalized, sanctioned, score, band, List.copyOf(matches),
                List.copyOf(signals), complete, List.copyOf(results));
    }

    public List<String> enabledProviderIds() {
        return enabledIds;
    }

    public record CompositeResult(
            String address,
            boolean sanctioned,
            int riskScore,
            RiskBand riskBand,
            List<ScreeningMatch> matches,
            List<RiskSignal> signals,
            boolean evidenceComplete,
            List<ScreeningResult> providers) {
    }
}
