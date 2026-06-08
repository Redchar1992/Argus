package com.argus.orchestrator.service;

import com.argus.orchestrator.agent.AgentOrchestrator;
import com.argus.orchestrator.dto.InvestigationDtos.InvestigationView;
import com.argus.orchestrator.dto.InvestigationDtos.SubmitResponse;
import com.argus.orchestrator.model.Investigation;
import com.argus.orchestrator.repository.InvestigationStore;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class InvestigationService {

    private final AgentOrchestrator orchestrator;
    private final InvestigationStore store;

    public InvestigationService(AgentOrchestrator orchestrator, InvestigationStore store) {
        this.orchestrator = orchestrator;
        this.store = store;
    }

    public SubmitResponse submit(String address, boolean runSync, String requestedBy) {
        String id = "inv_" + UUID.randomUUID().toString().substring(0, 12);
        orchestrator.createInvestigation(id, address, requestedBy);
        if (runSync) {
            // Synchronous: useful for tests / curl demos that want the result inline.
            orchestrator.run(id);
        } else {
            orchestrator.runAsync(id);
        }
        return new SubmitResponse(id, "RUNNING");
    }

    public InvestigationView get(String id) {
        Investigation inv = store.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No investigation " + id));
        return InvestigationView.from(inv);
    }

    public List<InvestigationView> recent(int limit) {
        return store.findRecent(limit).stream().map(InvestigationView::from).toList();
    }
}
