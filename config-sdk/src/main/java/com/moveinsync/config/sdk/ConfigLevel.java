package com.moveinsync.config.sdk;

/**
 * Hierarchy a configuration value can be defined at.
 *
 * <p>Precedence is {@code SHIFT -> OFFICE -> CLIENT -> GLOBAL}; the SDK resolves in that
 * order with at most three hash lookups.
 */
public enum ConfigLevel {

    GLOBAL(0),
    CLIENT(1),
    OFFICE(2),
    SHIFT(3);

    private final int precedence;

    ConfigLevel(int precedence) {
        this.precedence = precedence;
    }

    public int precedence() {
        return precedence;
    }

    /** Lenient parse used for wire data: unknown levels are skipped rather than fatal. */
    static ConfigLevel parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
