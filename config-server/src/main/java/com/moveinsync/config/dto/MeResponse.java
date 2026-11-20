package com.moveinsync.config.dto;

import com.moveinsync.config.security.Role;

public record MeResponse(
        String username,
        Role role,
        String clientId,
        String tokenId,
        String issuedAt,
        String expiresAt,
        long secondsRemaining
) {
}
