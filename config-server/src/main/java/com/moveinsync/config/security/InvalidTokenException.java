package com.moveinsync.config.security;

/**
 * A token that failed verification. Codes are stable so clients can distinguish
 * "log in again" ({@code TOKEN_EXPIRED}) from "your token is broken" ({@code TOKEN_INVALID}).
 */
public class InvalidTokenException extends RuntimeException {

    private final String code;

    public InvalidTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
