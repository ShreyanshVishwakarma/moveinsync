package com.moveinsync.config.exception;

import java.util.List;

/**
 * Reasons a proposed value was rejected. Every message references the key and level
 * only, never caller-supplied personal data.
 */
public class ValidationException extends ApiException {

    private final List<String> violations;

    public ValidationException(List<String> violations) {
        super(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Invalid configuration value(s)");
        this.violations = List.copyOf(violations);
    }

    public ValidationException(String violation) {
        this(List.of(violation));
    }

    public List<String> getViolations() {
        return violations;
    }
}
