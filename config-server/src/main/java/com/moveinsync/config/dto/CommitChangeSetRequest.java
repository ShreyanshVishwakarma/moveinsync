package com.moveinsync.config.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * An atomic change set: either every entry is published under one version or none is.
 */
public record CommitChangeSetRequest(
        @NotBlank
        @Size(max = 128)
        String author,

        @NotBlank
        @Size(max = 512)
        String reason,

        @NotEmpty
        @Size(max = 200, message = "a change set may contain at most 200 entries")
        List<@Valid OverrideChange> changes
) {
}
