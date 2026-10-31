package com.moveinsync.config.domain;

/**
 * Identity of a single override: the setting key plus the hierarchy position.
 */
public record OverrideScope(String key, ConfigLevel level, String entityId) {

    public static OverrideScope of(String key, ConfigLevel level, String entityId) {
        return new OverrideScope(key, level, entityId);
    }
}
