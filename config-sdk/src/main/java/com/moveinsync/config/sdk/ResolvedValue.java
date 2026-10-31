package com.moveinsync.config.sdk;

/**
 * A resolved value plus its provenance, so callers can log which level won.
 */
public record ResolvedValue(String key, String value, ConfigLevel sourceLevel, String sourceEntityId) {

    public boolean isDefault() {
        return sourceLevel == ConfigLevel.GLOBAL;
    }
}
