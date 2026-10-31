package com.moveinsync.config.sdk;

/**
 * Coordinates a read is resolved against. A service typically fixes {@code clientId}
 * once and supplies office/shift per request.
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

    public String entityIdFor(ConfigLevel level) {
        return switch (level) {
            case CLIENT -> clientId;
            case OFFICE -> officeId;
            case SHIFT -> shiftId;
            case GLOBAL -> "DEFAULT";
        };
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
