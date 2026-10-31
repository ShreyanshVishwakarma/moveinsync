package com.moveinsync.config.domain;

/**
 * Identity of a single override: the setting key, hierarchy position and owning tenant.
 */
public record OverrideScope(String key, ConfigLevel level, String entityId, String clientId) {

    public static OverrideScope of(String key, ConfigLevel level, String entityId) {
        return new OverrideScope(key, level, entityId, null);
    }

    public static OverrideScope of(String key, ConfigLevel level, String entityId, String clientId) {
        return new OverrideScope(key, level, entityId, clientId);
    }
}
