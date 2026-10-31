package com.moveinsync.config.security;

/**
 * Platform roles. Deliberately coarse - the case study asks for three:
 * employees read, operators publish, admins own the schema and see every tenant.
 */
public enum Role {

    /** Reads configuration for its own client only. */
    EMPLOYEE,

    /** Reads and publishes change sets for its own client. */
    OPERATOR,

    /** Full control, including definitions and cross-tenant reads. */
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
