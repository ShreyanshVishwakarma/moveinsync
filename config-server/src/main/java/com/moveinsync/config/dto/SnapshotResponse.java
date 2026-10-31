package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Full bootstrap payload. Clients that are too far behind (or starting cold) receive
 * this instead of a delta.
 *
 * <p>Instances are always tenant-filtered: a caller only ever sees overrides it owns.
 */
public record SnapshotResponse(
        long version,
        List<Definition> definitions,
        Map<String, Map<String, Map<String, String>>> overrides
) {
    public record Definition(
            String key,
            ConfigType type,
            String defaultValue,
            String validationRegex,
            String allowedValues,
            Double minValue,
            Double maxValue
    ) {
        static Definition from(ConfigSnapshot.DefinitionView view) {
            return new Definition(
                    view.key(),
                    view.type(),
                    view.defaultValue(),
                    view.validationRegex(),
                    view.allowedValues(),
                    view.minValue(),
                    view.maxValue()
            );
        }
    }

    /** Platform-admin view: every tenant. */
    public static SnapshotResponse from(ConfigSnapshot snapshot) {
        return from(snapshot, null);
    }

    /**
     * @param clientId tenant filter, or {@code null} for every tenant
     */
    public static SnapshotResponse from(ConfigSnapshot snapshot, String clientId) {
        List<Definition> definitions = snapshot.definitions().values().stream()
                .map(Definition::from)
                .toList();
        Map<String, Map<String, Map<String, String>>> overrides = new java.util.LinkedHashMap<>();
        snapshot.overridesFor(clientId).forEach((level, entities) -> {
            if (!entities.isEmpty()) {
                overrides.put(level.name(), entities);
            }
        });
        return new SnapshotResponse(snapshot.version(), definitions, overrides);
    }
}
