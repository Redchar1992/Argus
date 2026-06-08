package com.argus.orchestrator.controller;

import com.argus.orchestrator.dto.InvestigationDtos.InvestigationView;
import com.argus.orchestrator.dto.InvestigationDtos.SubmitRequest;
import com.argus.orchestrator.dto.InvestigationDtos.SubmitResponse;
import com.argus.orchestrator.service.InvestigationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<SubmitResponse> submit(
            @Valid @RequestBody SubmitRequest request,
            @RequestHeader(value = "X-Argus-User", required = false) String user) {
        boolean runSync = Boolean.TRUE.equals(request.runSync());
        SubmitResponse response = service.submit(request.address(), runSync,
                user == null ? "analyst" : user);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    public InvestigationView get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping
    public List<InvestigationView> recent(@RequestParam(defaultValue = "20") int limit) {
        return service.recent(limit);
    }
}
