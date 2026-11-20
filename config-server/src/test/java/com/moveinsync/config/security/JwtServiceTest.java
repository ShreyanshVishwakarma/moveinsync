package com.moveinsync.config.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token lifetime policy and signature verification, without a Spring context.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-for-hs256-must-be-32b!";
    private static final String ISSUER = "test-issuer";

    private JwtService service(Duration ttl, Duration skew) {
        return new JwtService(new JwtProperties(SECRET, ISSUER, ttl, skew));
    }

    @Test
    void issuesAndVerifiesATokenCarryingRoleAndTenant() {
        JwtService jwt = service(Duration.ofHours(1), Duration.ofSeconds(0));

        JwtService.IssuedToken issued = jwt.issue(new Actor(Role.OPERATOR, "acme-corp"), "operator");
        TokenClaims claims = jwt.verify(issued.token());

        assertThat(claims.subject()).isEqualTo("operator");
        assertThat(claims.role()).isEqualTo(Role.OPERATOR);
        assertThat(claims.clientId()).isEqualTo("acme-corp");
        assertThat(claims.tokenId()).isEqualTo(issued.tokenId());
        assertThat(Duration.between(claims.issuedAt(), claims.expiresAt())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsATamperedSignature() {
        JwtService jwt = service(Duration.ofHours(1), Duration.ZERO);
        String token = jwt.issue(new Actor(Role.ADMIN, null), "admin").token();
        // Flip the last character of the signature.
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> jwt.verify(tampered))
                .isInstanceOf(InvalidTokenException.class)
                .satisfies(ex -> assertThat(((InvalidTokenException) ex).getCode()).isEqualTo("TOKEN_INVALID"));
    }

    @Test
    void rejectsATokenFromAnotherIssuer() {
        JwtService issuerA = new JwtService(new JwtProperties(SECRET, "issuer-a", Duration.ofHours(1), Duration.ZERO));
        JwtService issuerB = new JwtService(new JwtProperties(SECRET, "issuer-b", Duration.ofHours(1), Duration.ZERO));
        String token = issuerA.issue(new Actor(Role.ADMIN, null), "admin").token();

        assertThatThrownBy(() -> issuerB.verify(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        // 1 ms lifetime, no clock skew: the token is expired by the time it is verified.
        JwtService jwt = service(Duration.ofMillis(1), Duration.ZERO);
        JwtService.IssuedToken issued = jwt.issue(new Actor(Role.EMPLOYEE, "acme-corp"), "employee");

        assertThatThrownBy(() -> jwt.verify(issued.token()))
                .isInstanceOf(InvalidTokenException.class)
                .satisfies(ex -> assertThat(((InvalidTokenException) ex).getCode()).isEqualTo("TOKEN_EXPIRED"));
    }

    @Test
    void capsALongRequestedLifetimeAtSevenDays() {
        // Configured default is the ceiling; a request asking for a year is clamped to it.
        JwtService jwt = service(JwtProperties.MAX_TTL, Duration.ZERO);

        JwtService.IssuedToken issued = jwt.issue(new Actor(Role.OPERATOR, "acme-corp"), "operator", Duration.ofDays(365));

        assertThat(issued.ttl()).isEqualTo(JwtProperties.MAX_TTL);
        assertThat(Duration.between(issued.issuedAt(), issued.expiresAt())).isEqualTo(Duration.ofDays(7));
        // And the token still verifies, because it is within policy.
        assertThat(jwt.verify(issued.token()).role()).isEqualTo(Role.OPERATOR);
    }

    @Test
    void defaultsToTheConfiguredTtlWhenTheRequestOmitsOne() {
        JwtService jwt = service(Duration.ofHours(2), Duration.ZERO);

        JwtService.IssuedToken issued = jwt.issue(new Actor(Role.OPERATOR, "acme-corp"), "operator", null);

        assertThat(issued.ttl()).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void aShorterRequestedLifetimeIsHonoured() {
        JwtService jwt = service(Duration.ofDays(7), Duration.ZERO);

        JwtService.IssuedToken issued = jwt.issue(new Actor(Role.OPERATOR, "acme-corp"), "operator", Duration.ofMinutes(15));

        assertThat(issued.ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void configurationRefusesALifetimeAboveSevenDays() {
        assertThatThrownBy(() -> new JwtProperties(SECRET, ISSUER, Duration.ofDays(8), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not exceed 7 days");
    }

    @Test
    void configurationRefusesAShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("too-short", ISSUER, Duration.ofDays(1), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void configurationFillsSensibleDefaults() {
        JwtProperties properties = new JwtProperties(null, null, null, null);

        assertThat(properties.accessTokenTtl()).isEqualTo(JwtProperties.MAX_TTL);
        assertThat(properties.issuer()).isEqualTo("moveinsync-config-server");
        assertThat(properties.clockSkew()).isEqualTo(Duration.ofSeconds(30));
    }
}
