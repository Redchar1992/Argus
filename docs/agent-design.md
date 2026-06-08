# Agent Design

This is the heart of Argus. It explains the loop, the tool schema, the prompt, and the
**honest** tradeoff between the two providers.

## The loop (`AgentOrchestrator`)

A bounded `plan → act → observe` loop. The orchestrator owns control flow; the
`LlmProvider` only decides the *next* action.

```java
for (int step = 1; step <= maxSteps; step++) {
    AgentAction action = llmProvider.nextAction(ctx);     // PLAN
    if (action.type() == FINISH) { recordFinish(...); break; }
    Map<String,Object> obs = toolClient.invoke(action.toolName(), action.toolArgs()); // ACT
    ctx.record(action.toolName(), action.toolArgs(), obs); // OBSERVE
    persistStep(...);                                      // auditable
}
```

Key properties:
- **Bounded.** `maxSteps` (default 8) caps runaway loops; hitting the cap forces a
  conservative `REVIEW` close-out.
- **Auditable.** Every step persists `{index, phase, thought, toolName, toolArgs,
  observation, timestamp, durationMs}`. The final decision persists `{decision, riskScore,
  riskBand, riskFactors, summary}`.
- **Provider-agnostic.** The *same* loop drives both providers. Swapping the brain does
  not change orchestration, persistence, or the API.

## Tool schema (`ToolSpec`)

The agent is offered a fixed catalog (kept in sync with `screening-tools-service`):

| Tool | Purpose | Key args |
|---|---|---|
| `sanctions_screen` | Is the subject (or a counterparty) on a list? | `addresses: string[]` |
| `address_profile` | Aggregate inflow/outflow/counterparties | `address` |
| `trace_transactions` | BFS the graph N hops; surface flagged exposure + path | `address`, `maxHops` |
| `risk_rules` | Turn gathered facts into a 0-100 AML score | `address`, volumes, `sanctionsDirectHit`, `minHopsToFlagged` |

Each tool returns structured JSON the brain reasons over. `trace_transactions` does a
**real** breadth-first walk with path reconstruction, so the exposure it reports is backed
by an actual chain of edges.

## Provider A — `LocalRuleAgentProvider` (default, no API key)

A genuine tool-selecting loop, not a canned response. On each call it inspects the
accumulated observations and decides the next action:

1. No `sanctions_screen` yet → screen the subject (a direct hit dominates everything).
2. No `address_profile` yet → profile activity (volume, counterparties).
3. No `trace_transactions` yet **and** (direct hit **or** activity warrants it) → trace.
   - **Judgement:** a tiny clean wallet (low volume, few counterparties, no hit) **skips**
     tracing and proceeds straight to scoring. This conditional is what makes it agentic
     rather than a fixed pipeline.
4. No `risk_rules` yet → score using everything gathered (including `minHopsToFlagged`
   derived from the trace).
5. Otherwise → `FINISH` with a decision derived from the score and policy bands
   (`>=60 ⇒ BLOCK`, `>=30 ⇒ REVIEW`, else `CLEAR`; a direct sanctions hit forces BLOCK).

Because step 3 branches on prior observations, two wallets take genuinely different paths
(verified in `AgentLoopTest` and by live curl — see README table).

**Why have it?** So the whole system is demonstrable with zero external dependencies and
deterministically — ideal for an interview walkthrough or CI — while still exercising a
real multi-step, observation-driven decision process.

## Provider B — `AnthropicLlmProvider` (real LLM tool-use)

Active with `ARGUS_LLM_PROVIDER=anthropic` and `ARGUS_ANTHROPIC_API_KEY` set. Uses the
Anthropic **Messages API** with tool-use:

- Request: `{ model, max_tokens, system, tools:[{name, description, input_schema}], messages }`.
- The four screening tools are rendered as tool schemas, plus a synthetic
  `finish_investigation` tool so the model returns its final decision through the same
  **structured** channel (no brittle free-text scraping).
- The orchestrator replays prior `(tool_use, tool_result)` pairs as conversation history,
  so on every turn the model sees its own past calls and their results.
- The response is parsed: a `tool_use` block for a screening tool → `CALL_TOOL`; a
  `tool_use` for `finish_investigation` → `FINISH`. Errors degrade safely to `REVIEW`.

### System prompt (verbatim)

```
You are Argus, an autonomous crypto-compliance investigator. Given a subject
wallet address, investigate it using the provided tools, then issue a final
compliance decision.

Method:
- Reason step by step. Call ONE tool at a time.
- Always begin by screening the subject for direct sanctions exposure.
- Profile the wallet's activity, and trace its transaction graph when the
  activity or any sanctions signal warrants it.
- Use risk_rules to convert gathered facts into a defensible score.
- When you have enough evidence, call finish_investigation with a decision of
  CLEAR, REVIEW, or BLOCK, a 0-100 riskScore, a riskBand, and the concrete
  riskFactors that justify it. A direct sanctions hit must result in BLOCK.

Be concise and auditable. Every tool call should have a clear purpose.
```

## The honest tradeoff

| | `local` (rule agent) | `anthropic` (LLM) |
|---|---|---|
| API key | none | required |
| Determinism | fully deterministic | model-dependent |
| Reasoning quality | fixed heuristics; predictable | adapts, explains, handles novel patterns |
| Cost / latency | free, ms | per-token, network |
| Good for | demos, CI, baseline, regression tests | the real "agentic" capability the JD asks for |

The local provider is **not** a fake of the LLM — it is a real, if simpler, decision
agent. The LLM provider is the production-grade brain. Both plug into the identical loop,
which is the point: the orchestration, tools, persistence, and audit trail are the durable
engineering; the brain is swappable.

## Where to look in code

- Loop: `agent-orchestrator-service/.../agent/AgentOrchestrator.java`
- Providers: `.../llm/LocalRuleAgentProvider.java`, `.../llm/AnthropicLlmProvider.java`
- Tool catalog/schema: `.../agent/ToolSpec.java`
- Action model: `.../agent/AgentAction.java`
- Tools: `screening-tools-service/.../service/*Service.java`
- Test driving the full loop: `.../agent/AgentLoopTest.java`
