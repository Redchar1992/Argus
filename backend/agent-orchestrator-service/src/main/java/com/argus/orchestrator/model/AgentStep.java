package com.argus.orchestrator.model;

import java.time.Instant;

/**
 * One auditable iteration of the agent loop. Stored as part of the Investigation
 * trace. Captures the thought, the chosen tool + args, and the raw observation
 * the tool returned (or, on the final step, the synthesized decision).
 */
public class AgentStep {

    private int index;
    private String phase;        // PLAN | ACT | OBSERVE | FINISH
    private String thought;
    private String toolName;
    private Object toolArgs;
    private Object observation;
    private String note;
    private Instant timestamp = Instant.now();
    private Long durationMs;

    public AgentStep() {
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getThought() {
        return thought;
    }

    public void setThought(String thought) {
        this.thought = thought;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Object getToolArgs() {
        return toolArgs;
    }

    public void setToolArgs(Object toolArgs) {
        this.toolArgs = toolArgs;
    }

    public Object getObservation() {
        return observation;
    }

    public void setObservation(Object observation) {
        this.observation = observation;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
