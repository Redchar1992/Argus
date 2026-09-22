package com.argus.cases.service;

import com.argus.cases.dto.CaseDtos.AuditView;
import com.argus.cases.dto.CaseDtos.CaseView;
import com.argus.cases.dto.CaseDtos.PersistCaseRequest;
import com.argus.cases.dto.CaseDtos.PolicyView;
import com.argus.cases.dto.CaseDtos.ReviewCaseRequest;
import com.argus.cases.model.AuditLogEntry;
import com.argus.cases.model.CaseRecord;
import com.argus.cases.model.ScreeningPolicy;
import com.argus.cases.repository.AuditLogRepository;
import com.argus.cases.repository.CaseRecordRepository;
import com.argus.cases.repository.ScreeningPolicyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CaseService {

    private static final String PENDING_REVIEW = "PENDING_REVIEW";
    private static final String NEEDS_INFO = "NEEDS_INFO";
    private static final String RESOLVED = "RESOLVED";
    private static final Set<String> REVIEW_ACTIONS = Set.of("CLEAR", "BLOCK", "REQUEST_INFO");

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
        // The orchestrator may retry its best-effort mirror call. Never reset a human
        // review that has already been opened or resolved when that happens.
        CaseRecord existing = caseRepository.findById(req.id()).orElse(null);
        if (existing != null) {
            return toView(existing);
        }
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

    /**
     * Applies a human decision without overwriting the original agent decision.
     * REQUEST_INFO keeps the case actionable; CLEAR/BLOCK close the review gate.
     */
    @Transactional
    public CaseView review(String id, ReviewCaseRequest req, String reviewer) {
        CaseRecord record = caseRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "No such case: " + id));
        String action = req.action() == null
                ? "" : req.action().trim().toUpperCase(Locale.ROOT);
        if (!REVIEW_ACTIONS.contains(action)) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Review action must be CLEAR, BLOCK, or REQUEST_INFO");
        }
        if (!"REVIEW".equalsIgnoreCase(record.getDecision())) {
            throw new ResponseStatusException(CONFLICT,
                    "Only cases with an agent decision of REVIEW can be reviewed");
        }
        if (!Set.of(PENDING_REVIEW, NEEDS_INFO).contains(record.getReviewStatus())) {
            throw new ResponseStatusException(CONFLICT,
                    "Case is no longer waiting for human review");
        }

        String actor = reviewer == null || reviewer.isBlank() ? "unknown" : reviewer;
        String note = normalizeNote(req.note());
        if (!"CLEAR".equals(action) && note == null) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "A note is required for BLOCK or REQUEST_INFO");
        }
        record.setReviewNote(note);
        record.setReviewedBy(actor);
        record.setReviewedAt(Instant.now());
        if ("REQUEST_INFO".equals(action)) {
            record.setReviewStatus(NEEDS_INFO);
            record.setReviewDecision(null);
        } else {
            record.setReviewStatus(RESOLVED);
            record.setReviewDecision(action);
        }
        CaseRecord saved = caseRepository.save(record);
        audit(actor, "CASE_REVIEWED", id,
                "action=" + action + noteSuffix(record.getReviewNote()));
        return toView(saved);
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
                c.getCreatedAt().toString(), c.getReviewStatus(), c.getReviewDecision(),
                c.getReviewNote(), c.getReviewedBy(),
                c.getReviewedAt() == null ? null : c.getReviewedAt().toString());
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String noteSuffix(String note) {
        if (note == null) {
            return "";
        }
        String suffix = " note=" + note;
        return suffix.length() <= 3900 ? suffix : suffix.substring(0, 3897) + "...";
    }
}
