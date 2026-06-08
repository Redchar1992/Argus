package com.argus.orchestrator.agent;

import java.util.List;
import java.util.Map;

/**
 * One decision returned by an {@link com.argus.orchestrator.llm.LlmProvider}: either
 * call a tool, or finish with a decision. This is the agent's "next step".
 *
 * @param type        CALL_TOOL or FINISH
 * @param thought     the reasoning the provider produced for this step (auditable)
 * @param toolName    when CALL_TOOL: which tool
 * @param toolArgs    when CALL_TOOL: the arguments to pass
 * @param decision    when FINISH: CLEAR | REVIEW | BLOCK
 * @param riskScore   when FINISH: final 0-100 score
 * @param riskBand    when FINISH: MINIMAL|LOW|MEDIUM|HIGH
 * @param riskFactors when FINISH: the human-readable factors behind the decision
 */
public record AgentAction(
        Type type,
        String thought,
        String toolName,
        Map<String, Object> toolArgs,
        String decision,
        Integer riskScore,
        String riskBand,
        List<String> riskFactors) {

    public enum Type {
        CALL_TOOL,
        FINISH
    }

    public static AgentAction callTool(String thought, String toolName, Map<String, Object> args) {
        return new AgentAction(Type.CALL_TOOL, thought, toolName, args, null, null, null, null);
    }

    public static AgentAction finish(String thought, String decision, int riskScore,
                                     String riskBand, List<String> riskFactors) {
        return new AgentAction(Type.FINISH, thought, null, null, decision, riskScore, riskBand, riskFactors);
    }
}
