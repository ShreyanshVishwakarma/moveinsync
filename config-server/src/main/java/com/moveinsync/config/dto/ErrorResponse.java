package com.moveinsync.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Stable error envelope. Deliberately excludes exception types, stack traces and any
 * request payload, so tenant data can never leak through an error response.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<String> details,
        String path
) {
    public static ErrorResponse of(int status, String code, String message, String path) {
        return new ErrorResponse(Instant.now(), status, code, message, List.of(), path);
    }

    public static ErrorResponse of(int status, String code, String message, List<String> details, String path) {
        return new ErrorResponse(Instant.now(), status, code, message, details, path);
    }

    public Map<String, Object> asMap() {
        return Map.of(
                "timestamp", timestamp.toString(),
                "status", status,
                "code", code,
                "message", message,
                "details", details,
                "path", path
        );
    }
}
