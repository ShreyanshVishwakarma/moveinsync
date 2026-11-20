package com.moveinsync.config.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT settings.
 *
 * <p>{@code access-token-ttl} is validated on startup: it must be positive and may never
 * exceed the hard 7-day ceiling, so a misconfiguration cannot mint effectively permanent
 * credentials.
 */
@ConfigurationProperties(prefix = "moveinsync.security.jwt")
public record JwtProperties(
        String secret,
        String issuer,
        Duration accessTokenTtl,
        Duration clockSkew
) {

    /** Hard ceiling from the requirements: tokens live at most 7 days. */
    public static final Duration MAX_TTL = Duration.ofDays(7);

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            secret = "dev-only-change-me-moveinsync-jwt-hs256-secret-key";
        }
        if (issuer == null || issuer.isBlank()) {
            issuer = "moveinsync-config-server";
        }
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()) {
            accessTokenTtl = MAX_TTL;
        }
        if (clockSkew == null || clockSkew.isNegative()) {
            clockSkew = Duration.ofSeconds(30);
        }

        // 256-bit minimum for HS256.
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "moveinsync.security.jwt.secret must be at least 32 bytes for HS256");
        }
        if (accessTokenTtl.compareTo(MAX_TTL) > 0) {
            throw new IllegalStateException(
                    "moveinsync.security.jwt.access-token-ttl must not exceed 7 days, got " + accessTokenTtl);
        }
    }
}
