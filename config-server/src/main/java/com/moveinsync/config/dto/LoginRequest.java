package com.moveinsync.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request. The caller cannot ask for a role — the stored user decides that.
 *
 * @param ttlSeconds optional shorter lifetime; the server clamps it to its own default and
 *                   to the 7-day ceiling
 */
public record LoginRequest(
        @NotBlank @Size(max = 128) String username,
        @NotBlank @Size(max = 256) String password,
        Long ttlSeconds
) {
}
