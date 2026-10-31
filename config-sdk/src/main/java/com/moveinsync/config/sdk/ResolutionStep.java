package com.moveinsync.config.sdk;

/**
 * One step of an explain trace.
 */
public record ResolutionStep(ConfigLevel level, String entityId, String value, boolean applied) {
}
