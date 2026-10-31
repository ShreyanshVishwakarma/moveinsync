package com.moveinsync.config.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotDiskCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsTheNewestSnapshot() {
        Path file = tempDir.resolve("nested/config_cache.json");

        try (SnapshotDiskCache cache = new SnapshotDiskCache(file)) {
            cache.save(snapshot(3));
            cache.save(snapshot(4));
            cache.flush();
        }

        try (SnapshotDiskCache reopened = new SnapshotDiskCache(file)) {
            Optional<Wire.Snapshot> loaded = reopened.load();

            assertThat(loaded).isPresent();
            assertThat(loaded.get().version()).isEqualTo(4);
            assertThat(ConfigState.fromSnapshot(loaded.get())
                    .resolve("booking_cutoff_minutes", new TenantContext("acme-corp", null, null)).value())
                    .isEqualTo("45");
        }
    }

    @Test
    void aMissingFileIsNotAnError() {
        try (SnapshotDiskCache cache = new SnapshotDiskCache(tempDir.resolve("absent.json"))) {
            assertThat(cache.load()).isEmpty();
        }
    }

    @Test
    void aCorruptFileIsIgnoredInsteadOfCrashingTheService() throws Exception {
        Path file = tempDir.resolve("config_cache.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        try (SnapshotDiskCache cache = new SnapshotDiskCache(file)) {
            assertThat(cache.load()).isEmpty();
        }
    }

    private Wire.Snapshot snapshot(long version) {
        return new Wire.Snapshot(
                version,
                java.util.List.of(new Wire.Definition("booking_cutoff_minutes", "DURATION", "60", null, null, null, null)),
                Map.of("CLIENT", Map.of("acme-corp", Map.of("booking_cutoff_minutes", "45")))
        );
    }
}
