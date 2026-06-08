package com.argus.auth.model;

/**
 * RBAC roles used across Argus.
 * ANALYST  - can submit investigations and read cases.
 * ADMIN    - everything an analyst can do, plus manage policies/tools and read the audit log.
 */
public enum Role {
    ANALYST,
    ADMIN
}
