package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One override mutation inside a change set. A null or blank {@code value} means
 * "remove this override and fall back to the next level".
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
        String value
) {
    public boolean isDelete() {
        return value == null || value.isBlank();
    }
}
