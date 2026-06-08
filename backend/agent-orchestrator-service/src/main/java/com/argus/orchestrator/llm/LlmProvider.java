package com.argus.orchestrator.llm;

import com.argus.orchestrator.agent.AgentAction;

/**
 * Abstraction over "the brain" of the agent. Given the current context (subject +
 * tool catalog + everything observed so far), decide the SINGLE next action:
 * call a tool, or finish with a decision.
 *
 * Two implementations exist behind @ConditionalOnProperty:
 *   - LocalRuleAgentProvider  (default): a genuine rule-based tool-selecting loop,
 *     no API key required. NOT a single canned string — it inspects observations
 *     and picks the next relevant un-run tool, then synthesizes a decision.
 *   - AnthropicLlmProvider    (argus.llm.provider=anthropic): real Claude tool-use,
 *     correct request/response shape, API key from ARGUS_ANTHROPIC_API_KEY.
 */
public interface LlmProvider {

    /** Stable id of this provider, recorded on the investigation for transparency. */
    String name();

    /** Decide the next action given the accumulated context. */
    AgentAction nextAction(AgentContext context);
}
