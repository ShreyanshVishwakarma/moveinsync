package com.moveinsync.config.sdk;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable configuration snapshot held by the SDK.
 *
 * <p>Never mutated after construction: the SDK swaps whole states with a single
 * {@code AtomicReference.set}, which is what makes multi-key change sets atomic for
 * readers. Resolution costs three map lookups plus one definition lookup.
 */
public final class ConfigState {

    private static final List<ConfigLevel> PROBE_ORDER =
            List.of(ConfigLevel.SHIFT, ConfigLevel.OFFICE, ConfigLevel.CLIENT);

    private final long version;
    private final Map<String, Definition> definitions;
    private final Map<ConfigLevel, Map<String, Map<String, String>>> overrides;

    private ConfigState(long version,
                        Map<String, Definition> definitions,
                        Map<ConfigLevel, Map<String, Map<String, String>>> overrides) {
        this.version = version;
        this.definitions = definitions;
        this.overrides = overrides;
    }

    public static ConfigState empty() {
        return new ConfigState(0L, Map.of(), emptyIndex());
    }

    public static ConfigState fromSnapshot(Wire.Snapshot snapshot) {
        if (snapshot == null) {
            return empty();
        }
        Map<String, Definition> definitionIndex = new HashMap<>();
        if (snapshot.definitions() != null) {
            for (Wire.Definition definition : snapshot.definitions()) {
                if (definition.key() == null) {
                    continue;
                }
                definitionIndex.put(definition.key(), Definition.from(definition));
            }
        }

        Map<ConfigLevel, Map<String, Map<String, String>>> index = emptyIndex();
        if (snapshot.overrides() != null) {
            snapshot.overrides().forEach((levelName, byEntity) -> {
                ConfigLevel level = ConfigLevel.parse(levelName);
                if (level == null || level == ConfigLevel.GLOBAL || byEntity == null) {
                    return;
                }
                Map<String, Map<String, String>> entities = new HashMap<>();
                byEntity.forEach((entityId, values) -> {
                    if (entityId != null && values != null) {
                        entities.put(entityId, immutableValues(values));
                    }
                });
                index.put(level, Map.copyOf(entities));
            });
        }

        return new ConfigState(
                Math.max(0L, snapshot.version()),
                Map.copyOf(definitionIndex),
                freeze(index)
        );
    }

    public long version() {
        return version;
    }

    public Map<String, Definition> definitions() {
        return definitions;
    }

    public Optional<Definition> definition(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    public boolean hasDefinition(String key) {
        return definitions.containsKey(key);
    }

    public Map<ConfigLevel, Map<String, Map<String, String>>> overrides() {
        return overrides;
    }

    /**
     * Most-specific-wins resolution. Throws when the key is unknown, because silently
     * returning null for a typo is the kind of bug that reaches production.
     */
    public ResolvedValue resolve(String key, TenantContext context) {
        Definition definition = definitions.get(key);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown configuration key");
        }
        for (ConfigLevel level : PROBE_ORDER) {
            String entityId = context.entityIdFor(level);
            String value = valueAt(level, entityId, key);
            if (value != null) {
                return new ResolvedValue(key, value, level, entityId);
            }
        }
        return new ResolvedValue(key, definition.defaultValue(), ConfigLevel.GLOBAL, "DEFAULT");
    }

    public List<ResolutionStep> explain(String key, TenantContext context) {
        if (!definitions.containsKey(key)) {
            throw new IllegalArgumentException("Unknown configuration key");
        }
        List<ResolutionStep> steps = new ArrayList<>(4);
        boolean applied = false;
        for (ConfigLevel level : PROBE_ORDER) {
            String entityId = context.entityIdFor(level);
            String value = valueAt(level, entityId, key);
            boolean winner = !applied && value != null;
            steps.add(new ResolutionStep(level, entityId, value, winner));
            applied = applied || winner;
        }
        steps.add(new ResolutionStep(ConfigLevel.GLOBAL, "DEFAULT",
                definitions.get(key).defaultValue(), !applied));
        return steps;
    }

    /**
     * Applies a delta that is exactly one version ahead. Callers must enforce the version
     * rules (see {@link StateApplier}); this method trusts its input.
     */
    public ConfigState applyDelta(Wire.Delta delta) {
        Map<ConfigLevel, Map<String, Map<String, String>>> mutable = new EnumMap<>(ConfigLevel.class);
        overrides.forEach((level, entities) -> mutable.put(level, new HashMap<>(entities)));

        if (delta.entries() != null) {
            for (Wire.Entry entry : delta.entries()) {
                ConfigLevel level = ConfigLevel.parse(entry.level());
                if (level == null || level == ConfigLevel.GLOBAL || entry.key() == null) {
                    continue;
                }
                String entityId = entry.entityId();
                if (entityId == null) {
                    continue;
                }
                Map<String, String> values = new HashMap<>(
                        mutable.getOrDefault(level, Map.of()).getOrDefault(entityId, Map.of()));

                if (entry.newValue() == null) {
                    values.remove(entry.key());
                } else {
                    values.put(entry.key(), entry.newValue());
                }

                Map<String, Map<String, String>> entities = mutable.computeIfAbsent(level, k -> new HashMap<>());
                if (values.isEmpty()) {
                    entities.remove(entityId);
                } else {
                    entities.put(entityId, Map.copyOf(values));
                }
            }
        }
        return new ConfigState(delta.version(), definitions, freeze(mutable));
    }

    /** Serialises the current state for the disk cache. */
    public Wire.Snapshot toSnapshot() {
        List<Wire.Definition> wireDefinitions = definitions.values().stream()
                .map(Definition::toWire)
                .toList();

        Map<String, Map<String, Map<String, String>>> wireOverrides = new HashMap<>();
        overrides.forEach((level, entities) -> {
            if (level == ConfigLevel.GLOBAL || entities.isEmpty()) {
                return;
            }
            wireOverrides.put(level.name(), new HashMap<>(entities));
        });

        return new Wire.Snapshot(version, wireDefinitions, wireOverrides);
    }

    public String valueAt(ConfigLevel level, String entityId, String key) {
        if (entityId == null) {
            return null;
        }
        Map<String, String> values = overrides.get(level).get(entityId);
        return values == null ? null : values.get(key);
    }

    public int overrideCount() {
        int total = 0;
        for (Map<String, Map<String, String>> entities : overrides.values()) {
            for (Map<String, String> values : entities.values()) {
                total += values.size();
            }
        }
        return total;
    }

    private static Map<ConfigLevel, Map<String, Map<String, String>>> emptyIndex() {
        Map<ConfigLevel, Map<String, Map<String, String>>> index = new EnumMap<>(ConfigLevel.class);
        for (ConfigLevel level : ConfigLevel.values()) {
            index.put(level, Map.of());
        }
        return index;
    }

    private static Map<ConfigLevel, Map<String, Map<String, String>>> freeze(
            Map<ConfigLevel, Map<String, Map<String, String>>> index) {

        Map<ConfigLevel, Map<String, Map<String, String>>> frozen = new EnumMap<>(ConfigLevel.class);
        frozen.put(ConfigLevel.GLOBAL, Map.of());
        for (Map.Entry<ConfigLevel, Map<String, Map<String, String>>> entry : index.entrySet()) {
            if (entry.getKey() == ConfigLevel.GLOBAL) {
                continue;
            }
            Map<String, Map<String, String>> entities = new HashMap<>();
            entry.getValue().forEach((entityId, values) -> entities.put(entityId, immutableValues(values)));
            frozen.put(entry.getKey(), Map.copyOf(entities));
        }
        return Map.copyOf(frozen);
    }

    private static Map<String, String> immutableValues(Map<String, String> values) {
        Map<String, String> copy = new HashMap<>(values.size());
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    /**
     * Definition metadata needed to resolve defaults and parse typed values.
     */
    public record Definition(
            String key,
            String type,
            String defaultValue,
            String validationRegex,
            String allowedValues,
            Double minValue,
            Double maxValue
    ) {
        static Definition from(Wire.Definition wire) {
            return new Definition(
                    wire.key(),
                    wire.type(),
                    wire.defaultValue(),
                    wire.validationRegex(),
                    wire.allowedValues(),
                    wire.minValue(),
                    wire.maxValue()
            );
        }

        Wire.Definition toWire() {
            return new Wire.Definition(key, type, defaultValue, validationRegex,
                    allowedValues, minValue, maxValue);
        }
    }
}
