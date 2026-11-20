package com.moveinsync.config.security;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Token revocation and cleanup.
 *
 * <p>A revoked token is rejected even though its signature is valid, which is what makes
 * "log out everywhere" and "disable this account" possible with stateless JWTs. Expired
 * revocations are purged so the table does not grow forever.
 */
@Service
public class TokenRevocationService {

    private final RevokedTokenRepository repository;

    public TokenRevocationService(RevokedTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void revoke(TokenClaims claims, String reason) {
        if (claims.tokenId() == null || repository.existsByTokenId(claims.tokenId())) {
            return;
        }
        repository.save(new RevokedToken(claims.tokenId(), claims.expiresAt(), reason));
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(String tokenId) {
        return tokenId != null && repository.existsByTokenId(tokenId);
    }

    /** Safe to call from a scheduled job; returns the number of rows removed. */
    @Transactional
    public long purgeExpired() {
        return repository.deleteByExpiresAtBefore(Instant.now());
    }
}
