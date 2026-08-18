package com.argus.tools.controller;

import com.argus.tools.dto.ToolDtos.AddressProfileRequest;
import com.argus.tools.dto.ToolDtos.AddressProfileResponse;
import com.argus.tools.dto.ToolDtos.RiskRulesRequest;
import com.argus.tools.dto.ToolDtos.RiskRulesResponse;
import com.argus.tools.dto.ToolDtos.SanctionsScreenRequest;
import com.argus.tools.dto.ToolDtos.SanctionsScreenResponse;
import com.argus.tools.dto.ToolDtos.TraceRequest;
import com.argus.tools.dto.ToolDtos.TraceResponse;
import com.argus.tools.model.ToolStatus;
import com.argus.tools.repository.ToolStatusRepository;
import com.argus.tools.service.AddressProfileService;
import com.argus.tools.service.RiskRulesService;
import com.argus.tools.service.SanctionsScreenService;
import com.argus.tools.service.TraceTransactionsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

/**
 * REST surface for the three screening tools plus the tool catalog. The agent
 * orchestrator calls /api/tools/{tool} over HTTP; the admin console calls the
 * catalog endpoints to enable/disable tools.
 */
@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final SanctionsScreenService sanctionsScreenService;
    private final TraceTransactionsService traceTransactionsService;
    private final RiskRulesService riskRulesService;
    private final AddressProfileService addressProfileService;
    private final ToolStatusRepository toolStatusRepository;

    public ToolsController(SanctionsScreenService sanctionsScreenService,
                           TraceTransactionsService traceTransactionsService,
                           RiskRulesService riskRulesService,
                           AddressProfileService addressProfileService,
                           ToolStatusRepository toolStatusRepository) {
        this.sanctionsScreenService = sanctionsScreenService;
        this.traceTransactionsService = traceTransactionsService;
        this.riskRulesService = riskRulesService;
        this.addressProfileService = addressProfileService;
        this.toolStatusRepository = toolStatusRepository;
    }

    @PostMapping("/sanctions_screen")
    @PreAuthorize("hasRole('SERVICE')")
    public SanctionsScreenResponse sanctionsScreen(@RequestBody SanctionsScreenRequest req) {
        requireEnabled("sanctions_screen");
        return sanctionsScreenService.screen(req);
    }

    @PostMapping("/trace_transactions")
    @PreAuthorize("hasRole('SERVICE')")
    public TraceResponse traceTransactions(@RequestBody TraceRequest req) {
        requireEnabled("trace_transactions");
        return traceTransactionsService.trace(req);
    }

    @PostMapping("/address_profile")
    @PreAuthorize("hasRole('SERVICE')")
    public AddressProfileResponse addressProfile(@RequestBody AddressProfileRequest req) {
        requireEnabled("address_profile");
        return addressProfileService.profile(req);
    }

    @PostMapping("/risk_rules")
    @PreAuthorize("hasRole('SERVICE')")
    public RiskRulesResponse riskRules(@RequestBody RiskRulesRequest req) {
        requireEnabled("risk_rules");
        return riskRulesService.evaluate(req);
    }

    // ---- catalog ----

    @GetMapping("/catalog")
    @PreAuthorize("hasAnyRole('SERVICE', 'ANALYST', 'ADMIN')")
    public List<ToolStatus> catalog() {
        return toolStatusRepository.findAll();
    }

    @PutMapping("/catalog/{toolId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ToolStatus setEnabled(@PathVariable String toolId, @RequestBody Map<String, Boolean> body) {
        ToolStatus status = toolStatusRepository.findById(toolId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Unknown tool: " + toolId));
        status.setEnabled(Boolean.TRUE.equals(body.getOrDefault("enabled", true)));
        return toolStatusRepository.save(status);
    }

    private void requireEnabled(String toolId) {
        boolean enabled = toolStatusRepository.findById(toolId)
                .map(ToolStatus::isEnabled)
                .orElse(true);
        if (!enabled) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Tool disabled by policy: " + toolId);
        }
    }
}
