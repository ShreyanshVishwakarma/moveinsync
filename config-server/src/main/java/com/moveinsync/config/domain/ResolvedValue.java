package com.moveinsync.config.domain;

/**
 * A resolved configuration value plus provenance, so "explain" mode can tell an
 * operator exactly which level supplied it.
 */
public record ResolvedValue(
        String key,
        String value,
        ConfigLevel sourceLevel,
        String sourceEntityId
) {
    public boolean isDefault() {
        return sourceLevel == ConfigLevel.GLOBAL;
    }
}
