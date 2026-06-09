package com.argus.orchestrator.controller;

import com.argus.orchestrator.dto.InvestigationDtos.InvestigationView;
import com.argus.orchestrator.dto.InvestigationDtos.SubmitRequest;
import com.argus.orchestrator.dto.InvestigationDtos.SubmitResponse;
import com.argus.orchestrator.service.InvestigationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The analyst console talks to these endpoints: submit a wallet, then poll the
 * investigation to render the live reasoning + tool-call timeline and final decision.
 */
@RestController
@RequestMapping("/api/investigations")
public class InvestigationController {

    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public ResponseEntity<SubmitResponse> submit(
            @Valid @RequestBody SubmitRequest request,
            @AuthenticationPrincipal Jwt principal,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean runSync = Boolean.TRUE.equals(request.runSync());
        // The requester is the authenticated subject; the bearer token is propagated to
        // the downstream (now-secured) tools/case services on the agent's internal calls.
        String user = principal != null ? principal.getSubject() : "analyst";
        SubmitResponse response = service.submit(request.address(), runSync, user, authorization);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public InvestigationView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'ADMIN')")
    public List<InvestigationView> recent(@RequestParam(defaultValue = "20") int limit) {
        return service.recent(limit);
    }
}
