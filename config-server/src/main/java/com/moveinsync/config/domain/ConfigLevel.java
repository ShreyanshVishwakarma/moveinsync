package com.moveinsync.config.domain;

/**
 * The hierarchy a configuration value can be defined at.
 *
 * <p>The enum ordinal encodes precedence: a higher {@link #precedence()} wins when the
 * same key is defined at several levels. Resolution order is
 * {@code SHIFT -> OFFICE -> CLIENT -> GLOBAL}.
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

    public boolean isOverridable() {
        return this != GLOBAL;
    }
}
