package com.argus.cases.service;

import com.argus.cases.dto.CaseDtos.AuditView;
import com.argus.cases.dto.CaseDtos.CaseView;
import com.argus.cases.dto.CaseDtos.PersistCaseRequest;
import com.argus.cases.dto.CaseDtos.PolicyView;
import com.argus.cases.model.AuditLogEntry;
import com.argus.cases.model.CaseRecord;
import com.argus.cases.model.ScreeningPolicy;
import com.argus.cases.repository.AuditLogRepository;
import com.argus.cases.repository.CaseRecordRepository;
import com.argus.cases.repository.ScreeningPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CaseService {

    private final CaseRecordRepository caseRepository;
    private final AuditLogRepository auditRepository;
    private final ScreeningPolicyRepository policyRepository;

    public CaseService(CaseRecordRepository caseRepository,
                       AuditLogRepository auditRepository,
                       ScreeningPolicyRepository policyRepository) {
        this.caseRepository = caseRepository;
        this.auditRepository = auditRepository;
        this.policyRepository = policyRepository;
    }

    public CaseView persist(PersistCaseRequest req, String actor) {
        String trustedActor = actor == null || actor.isBlank() ? "unknown" : actor;
        CaseRecord saved = caseRepository.save(new CaseRecord(
                req.id(), req.subjectAddress(), req.decision(), req.riskScore(),
                req.riskBand(), req.summary(), req.riskFactorsJson(), trustedActor));
        audit(trustedActor,
                "CASE_PERSISTED", req.id(),
                "decision=" + req.decision() + " score=" + req.riskScore());
        return toView(saved);
    }

    public List<CaseView> recentCases() {
        return caseRepository.findTop50ByOrderByCreatedAtDesc().stream().map(this::toView).toList();
    }

    public CaseView getCase(String id) {
        return caseRepository.findById(id).map(this::toView)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such case: " + id));
    }

    public List<AuditView> recentAudit() {
        return auditRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(a -> new AuditView(a.getId(), a.getActor(), a.getAction(),
                        a.getTarget(), a.getDetail(), a.getCreatedAt().toString()))
                .toList();
    }

    public AuditLogEntry audit(String actor, String action, String target, String detail) {
        return auditRepository.save(new AuditLogEntry(actor, action, target, detail));
    }

    public List<PolicyView> policies() {
        return policyRepository.findAll().stream()
                .map(p -> new PolicyView(p.getKey(), p.getDescription(), p.getValue()))
                .toList();
    }

    public PolicyView updatePolicy(String key, int value, String actor) {
        ScreeningPolicy policy = policyRepository.findById(key)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such policy: " + key));
        int old = policy.getValue();
        policy.setValue(value);
        ScreeningPolicy saved = policyRepository.save(policy);
        audit(actor == null ? "admin" : actor, "POLICY_UPDATED", key,
                "value " + old + " -> " + value);
        return new PolicyView(saved.getKey(), saved.getDescription(), saved.getValue());
    }

    private CaseView toView(CaseRecord c) {
        return new CaseView(c.getId(), c.getSubjectAddress(), c.getDecision(), c.getRiskScore(),
                c.getRiskBand(), c.getSummary(), c.getRiskFactorsJson(), c.getCreatedBy(),
                c.getCreatedAt().toString());
    }
}
