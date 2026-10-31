package com.moveinsync.config.sdk;

import java.time.Instant;

/**
 * Snapshot of SDK health, suitable for logging or exposing through Micrometer in the
 * host service.
 */
public record SdkStats(
        long version,
        int definitions,
        int overrides,
        long deltasApplied,
        long staleIgnored,
        long gapsDetected,
        long resyncs,
        long syncFailures,
        long reconnectScheduled,
        boolean connected,
        Instant lastSyncAt
) {
}
