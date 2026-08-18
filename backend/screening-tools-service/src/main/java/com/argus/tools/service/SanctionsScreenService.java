package com.argus.tools.service;

import com.argus.tools.dto.ToolDtos.ProviderEvidence;
import com.argus.tools.dto.ToolDtos.SanctionsHit;
import com.argus.tools.dto.ToolDtos.SanctionsRiskSignal;
import com.argus.tools.dto.ToolDtos.SanctionsScreenRequest;
import com.argus.tools.dto.ToolDtos.SanctionsScreenResponse;
import com.argus.tools.screening.AddressNormalizer;
import com.argus.tools.screening.CompositeScreeningProvider;
import com.argus.tools.screening.RiskBand;
import com.argus.tools.screening.ScreeningResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Thin tool adapter over the configured provider federation. */
@Service
public class SanctionsScreenService {

    private final CompositeScreeningProvider screening;

    public SanctionsScreenService(CompositeScreeningProvider screening) {
        this.screening = screening;
    }

    public SanctionsScreenResponse screen(SanctionsScreenRequest request) {
        List<String> normalized = request.addresses() == null ? List.of() : request.addresses().stream()
                .map(AddressNormalizer::normalize)
                .filter(address -> !address.isBlank())
                .distinct()
                .toList();
        List<CompositeScreeningProvider.CompositeResult> results =
                normalized.stream().map(screening::screen).toList();

        List<SanctionsHit> hits = results.stream().flatMap(result -> result.matches().stream())
                .map(match -> new SanctionsHit(match.address(), match.entity(), match.listSource(),
                        match.program(), match.severity()))
                .distinct()
                .toList();
        boolean directHit = !results.isEmpty() && results.get(0).sanctioned();
        int riskScore = results.stream().mapToInt(CompositeScreeningProvider.CompositeResult::riskScore)
                .max().orElse(0);
        RiskBand riskBand = results.stream().map(CompositeScreeningProvider.CompositeResult::riskBand)
                .reduce(RiskBand.LOW, RiskBand::worst);
        boolean evidenceComplete = !results.isEmpty() && results.stream()
                .allMatch(CompositeScreeningProvider.CompositeResult::evidenceComplete);

        List<ProviderEvidence> providerEvidence = new ArrayList<>();
        List<SanctionsRiskSignal> signals = new ArrayList<>();
        for (CompositeScreeningProvider.CompositeResult result : results) {
            for (ScreeningResult provider : result.providers()) {
                providerEvidence.add(new ProviderEvidence(result.address(), provider.providerId(),
                        provider.required(), provider.evidenceComplete(), provider.sanctioned(),
                        provider.riskScore(), provider.riskBand().name(), provider.datasetVersion(),
                        provider.error()));
                provider.signals().forEach(signal -> signals.add(new SanctionsRiskSignal(
                        result.address(), provider.providerId(), signal.category().name(),
                        signal.exposure().name(), signal.hopsAway(), signal.severity(),
                        signal.entity(), signal.detail())));
            }
        }

        return new SanctionsScreenResponse(normalized.size(), hits.size(), directHit, hits,
                riskScore, riskBand.name(), evidenceComplete, List.copyOf(providerEvidence),
                List.copyOf(signals));
    }
}
