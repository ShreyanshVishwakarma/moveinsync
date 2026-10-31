package com.moveinsync.config.domain;

/**
 * One step of an "explain" trace: whether a level had a value and whether it won.
 */
public record ResolutionStep(
        ConfigLevel level,
        String entityId,
        String value,
        boolean applied
) {
}
