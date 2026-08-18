package com.argus.orchestrator.controller;

import com.argus.orchestrator.security.WorkloadTokenService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/** Public verification keys for short-lived orchestrator workload credentials. */
@RestController
public class WorkloadJwksController {

    private final WorkloadTokenService tokens;

    public WorkloadJwksController(WorkloadTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping("/.well-known/workload-jwks.json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(tokens.publicJwkSet());
    }
}
