package com.moveinsync.config.dto;

import java.util.List;

/**
 * Answer to {@code GET /api/v1/config/snapshot?since=N}. Either a full snapshot
 * ({@code full=true}) or the change sets published after version N.
 */
public record SyncResponse(
        long version,
        long fromVersion,
        boolean full,
        SnapshotResponse snapshot,
        List<ChangeSetResponse> changeSets
) {
    public static SyncResponse full(SnapshotResponse snapshot, long fromVersion) {
        return new SyncResponse(snapshot.version(), fromVersion, true, snapshot, List.of());
    }

    public static SyncResponse delta(long version, long fromVersion, List<ChangeSetResponse> changeSets) {
        return new SyncResponse(version, fromVersion, false, null, changeSets);
    }
}
