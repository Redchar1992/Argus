package com.argus.tools.screening;

import com.argus.tools.model.SanctionedAddress;
import com.argus.tools.repository.SanctionedAddressRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/** Existing deterministic table-backed provider, retained for zero-infra product demos. */
@Component
public class LocalFixtureProvider implements ScreeningProvider {

    private final SanctionedAddressRepository repository;
    private final boolean required;

    public LocalFixtureProvider(SanctionedAddressRepository repository,
                                @Value("${argus.screening.local.required:true}") boolean required) {
        this.repository = repository;
        this.required = required;
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public boolean required() {
        return required;
    }

    @Override
    public ScreeningResult screen(String address) {
        return repository.findById(AddressNormalizer.normalize(address))
                .map(this::hit)
                .orElseGet(() -> new ScreeningResult(address, id(), required, false, 0,
                        RiskBand.LOW, List.of(), List.of(), true, "local-fixture-v1", null));
    }

    private ScreeningResult hit(SanctionedAddress entry) {
        ScreeningMatch match = new ScreeningMatch(entry.getAddress(), entry.getEntity(),
                entry.getListSource(), entry.getProgram(), entry.getSeverity());
        RiskSignal signal = new RiskSignal(RiskCategory.SANCTIONS, Exposure.DIRECT, 0,
                entry.getSeverity(), entry.getEntity(), entry.getListSource() + ":" + entry.getProgram());
        return new ScreeningResult(entry.getAddress(), id(), required, true, entry.getSeverity(),
                RiskBand.fromScore(entry.getSeverity()), List.of(match), List.of(signal), true,
                "local-fixture-v1", null);
    }
}
