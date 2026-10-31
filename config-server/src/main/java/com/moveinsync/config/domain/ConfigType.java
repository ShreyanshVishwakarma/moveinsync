package com.moveinsync.config.domain;

/**
 * Supported setting types. Each type owns a validation strategy
 * (see {@code com.moveinsync.config.service.validation}).
 */
public enum ConfigType {
    /** Integer/decimal value, optionally range bounded. */
    NUMBER,
    /** Non-negative duration expressed in minutes. */
    DURATION,
    /** Wall-clock window formatted {@code HH:mm-HH:mm}, start strictly before end. */
    TIME_WINDOW,
    /** One value from {@code allowedValues}. */
    ENUM,
    /** Comma separated list; every element is validated. */
    LIST,
    BOOLEAN,
    /** Free text, optionally constrained by a regex. */
    STRING
}
