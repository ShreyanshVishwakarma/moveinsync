package com.moveinsync.config.dto;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.snapshot.ConfigSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Full bootstrap payload. Clients that are too far behind (or starting cold) receive
 * this instead of a delta.
 */
public record SnapshotResponse(
        long version,
        List<Definition> definitions,
        Map<ConfigLevel, Map<String, Map<String, String>>> overrides
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

    public static SnapshotResponse from(ConfigSnapshot snapshot) {
        List<Definition> definitions = snapshot.definitions().values().stream()
                .map(Definition::from)
                .toList();
        return new SnapshotResponse(snapshot.version(), definitions, snapshot.overrideIndexView());
    }
}
