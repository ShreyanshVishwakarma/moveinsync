package com.moveinsync.config.security;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Tenant boundary enforcement.
 *
 * <p>Two rules, applied to every read and write:
 * <ul>
 *   <li>a non-admin caller can only name its own {@code clientId};</li>
 *   <li>office and shift overrides must carry the owning client, because entity ids are
 *       only unique inside a tenant.</li>
 * </ul>
 */
@Component
public class TenantGuard {

    /**
     * @return the client id the read should be scoped to (may be null for admins asking
     *         for global data)
     */
    public String effectiveClientId(Actor actor, String requestedClientId) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid API key is required");
        }
        if (actor.isAdmin()) {
            return blankToNull(requestedClientId);
        }
        if (actor.clientId() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TENANT_REQUIRED", "This API key is not bound to a tenant");
        }
        String requested = blankToNull(requestedClientId);
        if (requested != null && !requested.equals(actor.clientId())) {
            throw forbidden();
        }
        return actor.clientId();
    }

    /**
     * Determines the tenant an override belongs to, rejecting attempts to write into a
     * tenant the caller does not own.
     */
    public String tenantForWrite(Actor actor, ConfigLevel level, String entityId, String requestedClientId) {
        if (actor == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "A valid API key is required");
        }
        if (level == ConfigLevel.CLIENT) {
            if (!actor.canActFor(entityId)) {
                throw forbidden();
            }
            return entityId;
        }

        String tenant = blankToNull(requestedClientId);
        if (tenant == null) {
            tenant = actor.clientId();
        }
        if (tenant == null) {
            throw ApiException.badRequest("CLIENT_REQUIRED",
                    "Office and shift overrides must specify the owning clientId");
        }
        if (!actor.canActFor(tenant)) {
            throw forbidden();
        }
        return tenant;
    }

    public void requireAdmin(Actor actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Administrator role required");
        }
    }

    private ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "TENANT_FORBIDDEN",
                "This API key cannot access that tenant");
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
