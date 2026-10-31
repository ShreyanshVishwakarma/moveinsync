package com.moveinsync.config.dto;

import java.time.Instant;

public record VersionSummary(
        long version,
        String author,
        String reason,
        Instant createdAt,
        Long revertsToVersion
) {
}
