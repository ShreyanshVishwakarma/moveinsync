package com.moveinsync.config.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The authenticated identity attached to a request or WebSocket session.
 *
 * @param role     what the caller may do
 * @param clientId tenant the caller belongs to; {@code null} only for platform admins
 */
public record Actor(Role role, String clientId) {

    public static Actor system() {
        return new Actor(Role.ADMIN, null);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }

    /** True when this actor may act on the given tenant. Admins may act on any. */
    public boolean canActFor(String tenant) {
        if (isAdmin()) {
            return true;
        }
        return clientId != null && clientId.equals(tenant);
    }
}
