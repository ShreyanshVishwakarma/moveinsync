# Authentication — JWT

## What changed

The API used to authenticate every request with a hashed API key. It now issues **JWT bearer
tokens** from `POST /api/v1/auth/login`, and every endpoint is protected **per role**.
API keys still work for service accounts that cannot log in.

## Flow

```
POST /api/v1/auth/login  {username, password}
        │  BCrypt verify against `users`
        ▼
{JWT}  ── HS256, claims: sub, jti, role, clientId, iss, iat, exp
        │
        ▼
Authorization: Bearer <jwt>  ──▶  JwtAuthenticationFilter
                                     │ verify signature + issuer + exp
                                     │ reject if jti is on the revocation list
                                     ▼
                                  Actor(role, clientId)
                                     ▼
                                  SecurityConfig rules  ──▶  controller
```

## Endpoints

| Method | Path | Rule |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | public |
| GET | `/api/v1/auth/me` | any authenticated role |
| POST | `/api/v1/auth/logout` | any authenticated role |
| GET | `/api/v1/config/**` | EMPLOYEE, OPERATOR, ADMIN |
| GET | `/api/v1/definitions/**` | EMPLOYEE, OPERATOR, ADMIN |
| GET | `/ws/**` | EMPLOYEE, OPERATOR, ADMIN (tenant-filtered) |
| POST | `/api/v1/change-sets` | OPERATOR, ADMIN |
| POST | `/api/v1/change-sets/{version}/promote` | OPERATOR, ADMIN |
| POST | `/api/v1/rollbacks` | OPERATOR, ADMIN |
| GET | `/api/v1/rollbacks/**` | OPERATOR, ADMIN |
| GET | `/api/v1/change-sets/**` | ADMIN (cross-tenant audit trail) |
| POST | `/api/v1/definitions` | ADMIN |
| GET | `/actuator/**` | ADMIN (`health`, `info` public) |
| * | anything else | ADMIN — fails closed, never "any token" |

## Lifetime

- Default: **7 days** (`moveinsync.security.jwt.access-token-ttl: 7d`).
- Hard ceiling: **7 days**. Enforced three times:
  1. at startup, `JwtProperties` throws if the configured TTL is longer;
  2. at issue time, any requested `ttlSeconds` is clamped to it;
  3. at verification, a token whose `exp - iat` exceeds 7 days is rejected.
- Clients may request a **shorter** lifetime with `{"ttlSeconds": 900}` on login.

## Revocation

Stateless tokens cannot be "deleted", so logout records the token's `jti` in `revoked_tokens`.
The filter checks that table before trusting a token, which is what makes log out and
"disable this account" take effect immediately. Revocations are stored only until the token
would have expired anyway (`purgeExpired()`), so the table grows with admin actions, not logins.

## Errors

| Status | Code | When |
| --- | --- | --- |
| 401 | `UNAUTHENTICATED` | no token |
| 401 | `TOKEN_EXPIRED` | `exp` passed — sign in again |
| 401 | `TOKEN_INVALID` | bad signature, wrong issuer, unknown role, lifetime over 7 days |
| 401 | `TOKEN_REVOKED` | logged out |
| 401 | `INVALID_CREDENTIALS` | login failed; identical for unknown user and wrong password |
| 403 | `ROLE_FORBIDDEN` | valid token, role too low for this endpoint |
| 403 | `TENANT_FORBIDDEN` | valid token, but it names another tenant |

## Configuration

```yaml
moveinsync:
  security:
    jwt:
      secret: ${JWT_SECRET:dev-only-change-me-...}   # >= 32 bytes, HS256
      issuer: moveinsync-config-server
      access-token-ttl: 7d
      clock-skew: 30s
```

Set `JWT_SECRET` from the environment in any real deployment; the startup check refuses a
secret shorter than 32 bytes.

## Scope and what is not done yet

- No refresh tokens: the access token is long-lived (up to 7 days) by design here. A shorter
  TTL plus a refresh endpoint is the natural next step, and the ceiling already allows it.
- No asymmetric signing (`RS256`) or key rotation; a single HMAC secret is used.
- No per-user lockout/rate limiting on `/auth/login`.
- Users are seeded; there is no self-service registration or password reset.
- Revocation is checked per request with one indexed lookup; a cache would remove even that.

## Tests

| Suite | What it proves |
| --- | --- |
| `JwtServiceTest` (10) | issue/verify, tampered signature, foreign issuer, expiry, 7-day clamp, bad config |
| `JwtSecurityIntegrationTest` (8) | login, identical failure messages, tenant scoping, role checks on every endpoint, `/me`, tampered token, logout revocation, TTL clamp |
| `SecurityIntegrationTest` (5) | API-key path still works for service accounts |
