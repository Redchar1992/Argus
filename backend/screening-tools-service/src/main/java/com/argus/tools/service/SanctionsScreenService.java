package com.argus.tools.service;

import com.argus.tools.dto.ToolDtos.SanctionsHit;
import com.argus.tools.dto.ToolDtos.SanctionsScreenRequest;
import com.argus.tools.dto.ToolDtos.SanctionsScreenResponse;
import com.argus.tools.model.SanctionedAddress;
import com.argus.tools.repository.SanctionedAddressRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * sanctions_screen tool: checks a set of addresses against the seeded sanctions
 * list and reports direct hits. {@code directHit} is true when the PRIMARY (first)
 * address itself is listed — that distinction drives downstream risk scoring.
 */
@Service
public class SanctionsScreenService {

    private final SanctionedAddressRepository repository;

    public SanctionsScreenService(SanctionedAddressRepository repository) {
        this.repository = repository;
    }

    public SanctionsScreenResponse screen(SanctionsScreenRequest request) {
        List<String> normalized = request.addresses().stream()
                .map(a -> a.toLowerCase(Locale.ROOT).trim())
                .distinct()
                .toList();

        List<SanctionedAddress> matches = repository.findByAddressIn(normalized);

        List<SanctionsHit> hits = matches.stream()
                .map(m -> new SanctionsHit(m.getAddress(), m.getEntity(),
                        m.getListSource(), m.getProgram(), m.getSeverity()))
                .toList();

        String primary = normalized.isEmpty() ? null : normalized.get(0);
        boolean directHit = primary != null
                && matches.stream().anyMatch(m -> m.getAddress().equals(primary));

        return new SanctionsScreenResponse(normalized.size(), hits.size(), directHit, hits);
    }
}
