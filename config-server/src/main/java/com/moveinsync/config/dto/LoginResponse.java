package com.moveinsync.config.dto;

import com.moveinsync.config.security.Role;

/**
 * Issued credential. The token is only returned here; nothing is stored server-side, so
 * logout is a client-side discard plus (optionally) a shorter TTL.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String expiresAt,
        String tokenId,
        String username,
        Role role,
        String clientId
) {
}
