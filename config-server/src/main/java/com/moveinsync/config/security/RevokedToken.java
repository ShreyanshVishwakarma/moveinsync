package com.moveinsync.config.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Revoked token ids ({@code jti}).
 *
 * <p>Requests are stateless by design, so there is one small exception: a revoked token must
 * stop working immediately. Only tokens that are explicitly revoked are stored, so the table
 * grows with admin actions, not with logins.
 */
@Entity
@Table(name = "revoked_tokens")
public class RevokedToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_id", nullable = false, unique = true, length = 64)
    private String tokenId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt = Instant.now();

    @Column(name = "reason", length = 256)
    private String reason;

    protected RevokedToken() {
        // JPA
    }

    public RevokedToken(String tokenId, Instant expiresAt, String reason) {
        this.tokenId = tokenId;
        this.expiresAt = expiresAt;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getTokenId() {
        return tokenId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getReason() {
        return reason;
    }
}
