package com.argus.tools.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Enable/disable + metadata for a screening tool. The admin console toggles these;
 * the orchestrator reads the catalog so a disabled tool is never offered to the agent.
 */
@Entity
@Table(name = "tool_status")
public class ToolStatus {

    @Id
    @Column(name = "tool_id", length = 64)
    private String toolId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    protected ToolStatus() {
    }

    public ToolStatus(String toolId, String description, boolean enabled) {
        this.toolId = toolId;
        this.description = description;
        this.enabled = enabled;
    }

    public String getToolId() {
        return toolId;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
