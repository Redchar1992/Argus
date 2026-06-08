package com.argus.tools.service;

import com.argus.tools.dto.ToolDtos.AddressProfileRequest;
import com.argus.tools.dto.ToolDtos.AddressProfileResponse;
import com.argus.tools.model.TransactionEdge;
import com.argus.tools.repository.TransactionEdgeRepository;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * address_profile tool: aggregate inflow/outflow/counterparty stats for a single
 * address from the seeded graph. The agent feeds these numbers into risk_rules.
 */
@Service
public class AddressProfileService {

    private final TransactionEdgeRepository edgeRepository;

    public AddressProfileService(TransactionEdgeRepository edgeRepository) {
        this.edgeRepository = edgeRepository;
    }

    public AddressProfileResponse profile(AddressProfileRequest request) {
        String addr = request.address().toLowerCase(Locale.ROOT).trim();
        List<TransactionEdge> edges = edgeRepository
                .findByFromAddressInOrToAddressIn(List.of(addr), List.of(addr));

        double inflow = 0;
        double outflow = 0;
        Set<String> counterparties = new HashSet<>();
        for (TransactionEdge e : edges) {
            if (e.getToAddress().equals(addr)) {
                inflow += e.getAmountUsd();
                counterparties.add(e.getFromAddress());
            }
            if (e.getFromAddress().equals(addr)) {
                outflow += e.getAmountUsd();
                counterparties.add(e.getToAddress());
            }
        }
        counterparties.remove(addr);
        return new AddressProfileResponse(addr, inflow, outflow, counterparties.size(), edges.size());
    }
}
