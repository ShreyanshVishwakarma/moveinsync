package com.moveinsync.config.security;

import java.time.Instant;

/**
 * Verified JWT contents, used for {@code /auth/me} and for reasoning about expiry.
 */
public record TokenClaims(
        String subject,
        Role role,
        String clientId,
        String tokenId,
        Instant issuedAt,
        Instant expiresAt
) {
    public Actor toActor() {
        return new Actor(role, clientId);
    }
}
