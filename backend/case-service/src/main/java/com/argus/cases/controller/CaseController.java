package com.argus.cases.controller;

import com.argus.cases.dto.CaseDtos.AuditView;
import com.argus.cases.dto.CaseDtos.CaseView;
import com.argus.cases.dto.CaseDtos.PersistCaseRequest;
import com.argus.cases.dto.CaseDtos.PolicyView;
import com.argus.cases.dto.CaseDtos.UpdatePolicyRequest;
import com.argus.cases.service.CaseService;
import jakarta.validation.Valid;
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

    @PostMapping("/api/cases")
    public CaseView persist(@Valid @RequestBody PersistCaseRequest req) {
        return caseService.persist(req);
    }

    @GetMapping("/api/cases")
    public List<CaseView> recent() {
        return caseService.recentCases();
    }

    @GetMapping("/api/cases/{id}")
    public CaseView one(@PathVariable String id) {
        return caseService.getCase(id);
    }

    @GetMapping("/api/audit")
    public List<AuditView> audit() {
        return caseService.recentAudit();
    }

    @GetMapping("/api/policies")
    public List<PolicyView> policies() {
        return caseService.policies();
    }

    @PutMapping("/api/policies/{key}")
    public PolicyView updatePolicy(@PathVariable String key, @RequestBody UpdatePolicyRequest req) {
        return caseService.updatePolicy(key, req.value(), req.actor());
    }
}
