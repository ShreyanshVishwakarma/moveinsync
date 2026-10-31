package com.moveinsync.config.service;

import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.SnapshotResponse;

import java.util.List;

/**
 * Raised after a change set has been committed and the in-memory snapshot swapped,
 * but before the HTTP response returns. Listeners fan out over WebSocket.
 */
public record ConfigPublishedEvent(
        long version,
        SnapshotResponse snapshot,
        List<ChangeEntryResponse> entries
) {
}
