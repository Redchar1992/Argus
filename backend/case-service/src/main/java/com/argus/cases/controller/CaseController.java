package com.argus.cases.controller;

import com.argus.cases.dto.CaseDtos.AuditView;
import com.argus.cases.dto.CaseDtos.CaseView;
import com.argus.cases.dto.CaseDtos.PersistCaseRequest;
import com.argus.cases.dto.CaseDtos.PolicyView;
import com.argus.cases.dto.CaseDtos.UpdatePolicyRequest;
import com.argus.cases.service.CaseService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Case persistence + audit + policy management. These endpoints sit behind the
 * gateway under /api/cases, /api/audit, /api/policies.
 */
@RestController
public class CaseController {

    private final CaseService caseService;

    public CaseController(CaseService caseService) {
        this.caseService = caseService;
    }

    /** Written only by the orchestrator workload identity when an investigation completes. */
    @PostMapping("/api/cases")
    @PreAuthorize("hasRole('SERVICE')")
    public CaseView persist(@Valid @RequestBody PersistCaseRequest req,
                            @AuthenticationPrincipal Jwt principal) {
        String actor = principal == null ? "unknown" : principal.getClaimAsString("actor");
        return caseService.persist(req, actor);
    }

    @GetMapping("/api/cases")
    @PreAuthorize("hasAnyRole('SERVICE', 'ANALYST', 'ADMIN')")
    public List<CaseView> recent() {
        return caseService.recentCases();
    }

    @GetMapping("/api/cases/{id}")
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public CaseView one(@PathVariable String id) {
        return caseService.getCase(id);
    }

    /** The audit trail is compliance-sensitive: ADMIN only. */
    @GetMapping("/api/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public List<AuditView> audit() {
        return caseService.recentAudit();
    }

    @GetMapping("/api/policies")
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<PolicyView> policies() {
        return caseService.policies();
    }

    /** Editing screening policy drives agent behaviour, so it is ADMIN only. */
    @PutMapping("/api/policies/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    public PolicyView updatePolicy(@PathVariable String key, @RequestBody UpdatePolicyRequest req,
                                   Authentication authentication) {
        return caseService.updatePolicy(key, req.value(), authentication.getName());
    }
}
