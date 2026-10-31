package com.moveinsync.config.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Server-down cold start: a service that restarts while the config server is unreachable
 * must still answer reads from the last snapshot it persisted.
 */
class ConfigSdkOfflineTest {

    @TempDir
    Path tempDir;

    @Test
    void servesCachedValuesWhenTheServerIsUnreachable() throws Exception {
        Path cacheFile = tempDir.resolve("config_cache.json");

        try (SnapshotDiskCache cache = new SnapshotDiskCache(cacheFile)) {
            cache.save(new Wire.Snapshot(
                    42,
                    java.util.List.of(new Wire.Definition(
                            "booking_cutoff_minutes", "DURATION", "60", null, null, 0.0, 1440.0)),
                    Map.of("OFFICE", Map.of("blr-office-1", Map.of("booking_cutoff_minutes", "30")))
            ));
            cache.flush();
        }

        int closedPort = findClosedPort();

        try (ConfigSdk sdk = ConfigSdk.builder()
                .baseUrl("http://127.0.0.1:" + closedPort)
                .clientId("acme-corp")
                .officeId("blr-office-1")
                .cacheFile(cacheFile)
                .pollInterval(Duration.ofSeconds(1))
                .backoff(Duration.ofMillis(50), Duration.ofMillis(200), Duration.ofMillis(50))
                .build()) {

            sdk.start();

            // No waiting, no I/O on the read path: the cached snapshot is already live.
            assertThat(sdk.getInt("booking_cutoff_minutes")).isEqualTo(30);
            assertThat(sdk.getDuration("booking_cutoff_minutes")).isEqualTo(Duration.ofMinutes(30));
            assertThat(sdk.version()).isEqualTo(42);
            assertThat(sdk.resolve("booking_cutoff_minutes",
                    new TenantContext("acme-corp", "hyd-office-1", null)).value()).isEqualTo("60");
        }
    }

    private int findClosedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
