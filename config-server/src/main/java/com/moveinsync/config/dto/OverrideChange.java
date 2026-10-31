package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One override mutation inside a change set. A null or blank {@code value} means
 * "remove this override and fall back to the next level".
 *
 * <p>{@code clientId} identifies the owning tenant for OFFICE and SHIFT overrides.
 * It may be omitted when the caller's API key is bound to a tenant; platform admins must
 * supply it. For CLIENT-level overrides it must equal {@code entityId}.
 */
public record OverrideChange(
        @NotBlank
        @Size(max = 128)
        String key,

        @NotNull
        ConfigLevel level,

        @NotBlank
        @Size(max = 128)
        String entityId,

        @Size(max = 512)
        String value,

        @Size(max = 128)
        String clientId
) {
    public OverrideChange(String key, ConfigLevel level, String entityId, String value) {
        this(key, level, entityId, value, null);
    }

    public boolean isDelete() {
        return value == null || value.isBlank();
    }
}
