package com.moveinsync.config.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>The token is the only credential the API accepts: it carries the subject, the role and
 * the tenant, so a request never has to look anything up to authorise a read. Expiry is
 * capped at 7 days at issue time <em>and</em> re-checked when parsing, so a token minted by a
 * buggy or compromised issuer cannot outlive the policy.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_CLIENT = "clientId";
    private static final String CLAIM_USERNAME = "username";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        log.info("JWT issuer={} ttl={} (max {})", properties.issuer(), properties.accessTokenTtl(), JwtProperties.MAX_TTL);
    }

    /**
     * @param actor    role and tenant to embed
     * @param subject  username the token was issued to
     * @param ttl      requested lifetime; clamped to the 7-day ceiling
     */
    public IssuedToken issue(Actor actor, String subject, Duration ttl) {
        Duration effective = clamp(ttl == null ? properties.accessTokenTtl() : ttl);
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(effective);
        String tokenId = UUID.randomUUID().toString();

        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(subject)
                .id(tokenId)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .claim(CLAIM_ROLE, actor.role().name())
                .claim(CLAIM_CLIENT, actor.clientId())
                .claim(CLAIM_USERNAME, subject)
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, tokenId, issuedAt, expiresAt, effective, actor, subject);
    }

    public IssuedToken issue(Actor actor, String subject) {
        return issue(actor, subject, properties.accessTokenTtl());
    }

    /**
     * @throws InvalidTokenException when the signature, issuer, expiry or lifetime is bad
     */
    public TokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("TOKEN_MISSING", "No access token was supplied");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .clockSkewSeconds(properties.clockSkew().toSeconds())
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();

            Instant issuedAt = claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
            Instant expiresAt = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            if (issuedAt == null || expiresAt == null) {
                throw new InvalidTokenException("TOKEN_INVALID", "Access token is missing timestamps");
            }
            // Defense in depth: never honour a token with a lifetime beyond policy.
            if (Duration.between(issuedAt, expiresAt).compareTo(JwtProperties.MAX_TTL) > 0) {
                throw new InvalidTokenException("TOKEN_INVALID", "Access token lifetime exceeds the 7-day policy");
            }

            String rawRole = claims.get(CLAIM_ROLE, String.class);
            Role role;
            try {
                role = Role.valueOf(rawRole);
            } catch (RuntimeException ex) {
                throw new InvalidTokenException("TOKEN_INVALID", "Access token has an unknown role");
            }

            return new TokenClaims(
                    claims.getSubject(),
                    role,
                    claims.get(CLAIM_CLIENT, String.class),
                    claims.getId(),
                    issuedAt,
                    expiresAt
            );
        } catch (ExpiredJwtException ex) {
            throw new InvalidTokenException("TOKEN_EXPIRED", "Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("TOKEN_INVALID", "Access token could not be verified");
        }
    }

    /** Useful for clients that want to plan a refresh without decoding the token. */
    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    private Duration clamp(Duration requested) {
        if (requested == null || requested.isZero() || requested.isNegative()) {
            return properties.accessTokenTtl();
        }
        return requested.compareTo(JwtProperties.MAX_TTL) > 0 ? JwtProperties.MAX_TTL : requested;
    }

    public record IssuedToken(
            String token,
            String tokenId,
            Instant issuedAt,
            Instant expiresAt,
            Duration ttl,
            Actor actor,
            String subject
    ) {
    }
}
