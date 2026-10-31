package com.moveinsync.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RollbackRequest(
        @NotNull
        Long targetVersion,

        @NotBlank
        @Size(max = 128)
        String author,

        @NotBlank
        @Size(max = 512)
        String reason,

        /** When true, compute and return the diff without publishing anything. */
        boolean dryRun
) {
}
