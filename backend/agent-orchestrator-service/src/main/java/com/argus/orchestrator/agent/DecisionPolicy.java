package com.argus.orchestrator.agent;

/**
 * The decision bands the agent maps a final risk score onto. These come from the
 * admin-editable {@code screening_policy} (block/review thresholds) so that editing
 * the policy actually changes agent behaviour — they are NOT hardcoded constants.
 *
 * @param blockThreshold  score at/above which a wallet is BLOCKED
 * @param reviewThreshold score at/above which a wallet is sent to manual REVIEW
 */
public record DecisionPolicy(int blockThreshold, int reviewThreshold) {

    /** Conservative fallback used only when the policy service is unreachable. */
    public static DecisionPolicy defaults() {
        return new DecisionPolicy(60, 30);
    }
}
