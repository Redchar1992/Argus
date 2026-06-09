package com.argus.orchestrator.agent;

import com.argus.orchestrator.client.CaseServiceClient;
import com.argus.orchestrator.client.ToolClient;
import com.argus.orchestrator.llm.AgentContext;
import com.argus.orchestrator.llm.LlmProvider;
import com.argus.orchestrator.model.AgentStep;
import com.argus.orchestrator.model.Investigation;
import com.argus.orchestrator.repository.InvestigationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * THE CENTERPIECE. Runs the real plan -> act -> observe loop:
 *
 *   loop (bounded by maxSteps):
 *     action = llm.nextAction(context)         // PLAN: ask the brain what to do
 *     if action == FINISH: record + break      // brain decided it has enough
 *     observation = toolClient.invoke(...)      // ACT: run the chosen tool over REST
 *     context.record(tool, args, observation)   // OBSERVE: feed result back
 *     persist step                              // every step is auditable
 *
 * The same loop drives BOTH the local rule provider and the Anthropic provider —
 * only the brain swaps out. Each step (thought, tool, args, observation, timing)
 * is persisted to the trace store as it happens, so the frontend can poll and
 * render the investigation live.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final LlmProvider llmProvider;
    private final ToolClient toolClient;
    private final InvestigationStore store;
    private final CaseServiceClient caseServiceClient;
    private final int defaultMaxSteps;

    public AgentOrchestrator(LlmProvider llmProvider,
                             ToolClient toolClient,
                             InvestigationStore store,
                             CaseServiceClient caseServiceClient,
                             @Value("${argus.agent.max-steps:8}") int defaultMaxSteps) {
        this.llmProvider = llmProvider;
        this.toolClient = toolClient;
        this.store = store;
        this.caseServiceClient = caseServiceClient;
        this.defaultMaxSteps = defaultMaxSteps;
    }

    /** Creates the investigation record up front (status RUNNING) and returns it. */
    public Investigation createInvestigation(String id, String subjectAddress, String requestedBy) {
        Investigation inv = new Investigation();
        inv.setId(id);
        inv.setSubjectAddress(subjectAddress.toLowerCase().trim());
        inv.setStatus("RUNNING");
        inv.setLlmProvider(llmProvider.name());
        inv.setMaxSteps(defaultMaxSteps);
        inv.setRequestedBy(requestedBy);
        inv.setCreatedAt(Instant.now());
        return store.save(inv);
    }

    /** Runs the loop asynchronously so the submit endpoint can return immediately. */
    @Async
    public void runAsync(String investigationId, String bearerToken) {
        run(investigationId, bearerToken);
    }

    /** Convenience overload (no propagated caller token) used by tests. */
    public Investigation run(String investigationId) {
        return run(investigationId, null);
    }

    /**
     * The actual loop. Public + synchronous so tests can drive it deterministically.
     * The {@code bearerToken} (the caller's {@code Authorization} header) is propagated
     * on every service-to-service call so the now-secured screening-tools-service and
     * case-service authorise the request as the originating analyst/admin.
     */
    public Investigation run(String investigationId, String bearerToken) {
        Investigation inv = store.findById(investigationId)
                .orElseThrow(() -> new IllegalArgumentException("No investigation " + investigationId));
        AgentContext ctx = new AgentContext(inv.getSubjectAddress(), ToolSpec.defaultCatalog());

        try {
            for (int step = 1; step <= inv.getMaxSteps(); step++) {
                long t0 = System.currentTimeMillis();

                // PLAN
                AgentAction action = llmProvider.nextAction(ctx);

                if (action.type() == AgentAction.Type.FINISH) {
                    recordFinish(inv, step, action, System.currentTimeMillis() - t0);
                    complete(inv, bearerToken);
                    return store.save(inv);
                }

                // ACT
                Map<String, Object> observation =
                        toolClient.invoke(action.toolName(), action.toolArgs(), bearerToken);

                // OBSERVE
                ctx.record(action.toolName(), action.toolArgs(), observation);
                recordToolStep(inv, step, action, observation, System.currentTimeMillis() - t0);
                store.save(inv);
            }

            // Hit the step cap without finishing -> force a conservative REVIEW close-out.
            forceReviewOnStepCap(inv);
            complete(inv, bearerToken);
            return store.save(inv);

        } catch (Exception e) {
            log.error("Investigation {} failed", investigationId, e);
            inv.setStatus("FAILED");
            inv.setError(e.getMessage());
            inv.setCompletedAt(Instant.now());
            return store.save(inv);
        }
    }

    private void recordToolStep(Investigation inv, int index, AgentAction action,
                                Map<String, Object> observation, long durationMs) {
        AgentStep plan = new AgentStep();
        plan.setIndex(index);
        plan.setPhase("ACT");
        plan.setThought(action.thought());
        plan.setToolName(action.toolName());
        plan.setToolArgs(action.toolArgs());
        plan.setObservation(observation);
        plan.setDurationMs(durationMs);
        inv.addStep(plan);
    }

    private void recordFinish(Investigation inv, int index, AgentAction action, long durationMs) {
        AgentStep finish = new AgentStep();
        finish.setIndex(index);
        finish.setPhase("FINISH");
        finish.setThought(action.thought());
        finish.setNote("decision=" + action.decision());
        finish.setDurationMs(durationMs);
        inv.addStep(finish);

        inv.setDecision(action.decision());
        inv.setRiskScore(action.riskScore());
        inv.setRiskBand(action.riskBand());
        inv.setRiskFactors(action.riskFactors() == null ? List.of() : action.riskFactors());
        inv.setSummary(buildSummary(inv));
    }

    private void forceReviewOnStepCap(Investigation inv) {
        AgentStep step = new AgentStep();
        step.setIndex(inv.getSteps().size() + 1);
        step.setPhase("FINISH");
        step.setThought("Reached the maximum step budget (" + inv.getMaxSteps()
                + ") without a conclusion. Closing out as REVIEW for human follow-up.");
        step.setNote("decision=REVIEW (step-cap)");
        inv.addStep(step);
        inv.setDecision("REVIEW");
        inv.setRiskScore(inv.getRiskScore() == null ? 50 : inv.getRiskScore());
        inv.setRiskBand("MEDIUM");
        inv.setRiskFactors(List.of("Investigation hit the step budget without converging."));
        inv.setSummary(buildSummary(inv));
    }

    private void complete(Investigation inv, String bearerToken) {
        inv.setStatus("COMPLETED");
        inv.setCompletedAt(Instant.now());
        caseServiceClient.mirrorCase(inv.getId(), inv.getSubjectAddress(), inv.getDecision(),
                inv.getRiskScore() == null ? 0 : inv.getRiskScore(), inv.getRiskBand(),
                inv.getSummary(), inv.getRiskFactors(), inv.getRequestedBy(), bearerToken);
    }

    private String buildSummary(Investigation inv) {
        return "Agent (" + inv.getLlmProvider() + ") ran " + inv.getSteps().size()
                + " step(s) on " + inv.getSubjectAddress() + " and decided " + inv.getDecision()
                + " with risk score " + inv.getRiskScore() + ".";
    }
}
