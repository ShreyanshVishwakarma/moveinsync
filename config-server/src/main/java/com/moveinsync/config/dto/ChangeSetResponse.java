package com.moveinsync.config.dto;

import java.time.Instant;
import java.util.List;

public record ChangeSetResponse(
        long version,
        String author,
        String reason,
        Instant createdAt,
        Long revertsToVersion,
        Long promotedFromVersion,
        List<ChangeEntryResponse> entries
) {
}
