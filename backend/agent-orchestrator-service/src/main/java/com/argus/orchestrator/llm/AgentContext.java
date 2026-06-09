package com.argus.orchestrator.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The running state the LLM provider sees when deciding the next action: the
 * subject address, the tools available, and the full history of (tool -> observation)
 * pairs gathered so far. Both providers receive the SAME context object, so the
 * comparison between them is apples-to-apples.
 */
public class AgentContext {

    private final String subjectAddress;
    private final List<com.argus.orchestrator.agent.ToolSpec> tools;
    private final List<ObservedCall> history = new ArrayList<>();
    private final com.argus.orchestrator.agent.DecisionPolicy decisionPolicy;

    public AgentContext(String subjectAddress, List<com.argus.orchestrator.agent.ToolSpec> tools) {
        this(subjectAddress, tools, com.argus.orchestrator.agent.DecisionPolicy.defaults());
    }

    public AgentContext(String subjectAddress,
                        List<com.argus.orchestrator.agent.ToolSpec> tools,
                        com.argus.orchestrator.agent.DecisionPolicy decisionPolicy) {
        this.subjectAddress = subjectAddress;
        this.tools = tools;
        this.decisionPolicy = decisionPolicy == null
                ? com.argus.orchestrator.agent.DecisionPolicy.defaults() : decisionPolicy;
    }

    public String getSubjectAddress() {
        return subjectAddress;
    }

    /** The admin-editable decision bands in effect for this investigation. */
    public com.argus.orchestrator.agent.DecisionPolicy getDecisionPolicy() {
        return decisionPolicy;
    }

    public List<com.argus.orchestrator.agent.ToolSpec> getTools() {
        return tools;
    }

    public List<ObservedCall> getHistory() {
        return history;
    }

    public void record(String toolName, Map<String, Object> args, Map<String, Object> observation) {
        history.add(new ObservedCall(toolName, args, observation));
    }

    public boolean hasRun(String toolName) {
        return history.stream().anyMatch(c -> c.toolName().equals(toolName));
    }

    /** Latest observation for a tool, or null if not yet run. */
    public Map<String, Object> latestObservation(String toolName) {
        Map<String, Object> result = null;
        for (ObservedCall c : history) {
            if (c.toolName().equals(toolName)) {
                result = c.observation();
            }
        }
        return result;
    }

    /** Compact, ordered map of tool -> observation for prompt rendering. */
    public Map<String, Object> asObservationMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ObservedCall c : history) {
            m.put(c.toolName(), c.observation());
        }
        return m;
    }

    public record ObservedCall(String toolName, Map<String, Object> args, Map<String, Object> observation) {
    }
}
