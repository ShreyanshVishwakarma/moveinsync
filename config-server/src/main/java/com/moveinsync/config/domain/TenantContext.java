package com.moveinsync.config.domain;

/**
 * The four coordinates a read can be resolved against. Any level below CLIENT may
 * be absent; resolution simply skips missing scopes.
 */
public record TenantContext(String clientId, String officeId, String shiftId) {

    public static TenantContext global() {
        return new TenantContext(null, null, null);
    }

    public TenantContext {
        clientId = blankToNull(clientId);
        officeId = blankToNull(officeId);
        shiftId = blankToNull(shiftId);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** The entity id at a given level, or null when that level does not apply. */
    public String entityIdFor(ConfigLevel level) {
        return switch (level) {
            case CLIENT -> clientId;
            case OFFICE -> officeId;
            case SHIFT -> shiftId;
            case GLOBAL -> "DEFAULT";
        };
    }
}
