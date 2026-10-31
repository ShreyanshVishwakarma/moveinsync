package com.moveinsync.config.snapshot;

import com.moveinsync.config.domain.ConfigDefinition;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigOverride;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.domain.ResolutionStep;
import com.moveinsync.config.domain.ResolvedValue;
import com.moveinsync.config.domain.TenantContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable, fully indexed view of every definition and override at one version.
 *
 * <p>Reads are lock-free: the reference to a snapshot is swapped atomically by
 * {@link SnapshotStore}, and this object never mutates afterwards. A read therefore
 * costs at most three hash lookups (SHIFT, OFFICE, CLIENT) plus one map lookup for
 * the global default, i.e. O(hierarchy depth) with a fixed depth of 4.
 *
 * <p>Memory is O(definitions + overrides); the index duplicates override values as
 * {@code level -> entityId -> key -> value} so resolution never scans.
 */
public final class ConfigSnapshot {

    private static final Map<ConfigLevel, Map<String, Map<String, String>>> NO_OVERRIDES =
            emptyOverrideIndex();

    /** Strict precedence probe order: most specific first. */
    private static final List<ConfigLevel> PROBE_ORDER =
            List.of(ConfigLevel.SHIFT, ConfigLevel.OFFICE, ConfigLevel.CLIENT);

    private final long version;
    private final Map<String, DefinitionView> definitions;
    private final Map<ConfigLevel, Map<String, Map<String, String>>> overrides;

    private ConfigSnapshot(long version,
                           Map<String, DefinitionView> definitions,
                           Map<ConfigLevel, Map<String, Map<String, String>>> overrides) {
        this.version = version;
        this.definitions = definitions;
        this.overrides = overrides;
    }

    public static ConfigSnapshot empty() {
        return new ConfigSnapshot(0L, Map.of(), NO_OVERRIDES);
    }

    public static ConfigSnapshot of(long version,
                                    List<ConfigDefinition> definitions,
                                    List<ConfigOverride> overrides) {
        Map<String, DefinitionView> definitionIndex = new HashMap<>(definitions.size() * 2);
        for (ConfigDefinition definition : definitions) {
            definitionIndex.put(definition.getKey(), DefinitionView.from(definition));
        }

        Map<ConfigLevel, Map<String, Map<String, String>>> overrideIndex = emptyOverrideIndex();
        for (ConfigOverride override : overrides) {
            overrideIndex.get(override.getLevel())
                    .computeIfAbsent(override.getEntityId(), ignored -> new HashMap<>(8))
                    .put(override.getConfigKey(), override.getValue());
        }
        return new ConfigSnapshot(version, Map.copyOf(definitionIndex), freeze(overrideIndex));
    }

    public long version() {
        return version;
    }

    public Map<String, DefinitionView> definitions() {
        return definitions;
    }

    public Optional<DefinitionView> definition(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    public boolean hasDefinition(String key) {
        return definitions.containsKey(key);
    }

    /**
     * Most-specific-wins resolution: SHIFT, then OFFICE, then CLIENT, then the
     * global default.
     */
    public ResolvedValue resolve(String key, TenantContext context) {
        DefinitionView definition = definitions.get(key);
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

    /**
     * Produces the full precedence trace for a key, including levels that were
     * skipped because the context had no id.
     */
    public List<ResolutionStep> explain(String key, TenantContext context) {
        if (!definitions.containsKey(key)) {
            throw new IllegalArgumentException("Unknown configuration key");
        }
        List<ResolutionStep> steps = new ArrayList<>(4);
        boolean alreadyApplied = false;

        for (ConfigLevel level : PROBE_ORDER) {
            String entityId = context.entityIdFor(level);
            String value = valueAt(level, entityId, key);
            boolean applies = !alreadyApplied && value != null;
            steps.add(new ResolutionStep(level, entityId, value, applies));
            alreadyApplied = alreadyApplied || applies;
        }

        DefinitionView definition = definitions.get(key);
        steps.add(new ResolutionStep(ConfigLevel.GLOBAL, "DEFAULT", definition.defaultValue(), !alreadyApplied));
        return steps;
    }

    /** Raw override lookup; package-visible for the API projection layer. */
    public String valueAt(ConfigLevel level, String entityId, String key) {
        if (entityId == null) {
            return null;
        }
        Map<String, String> values = overrides.get(level).get(entityId);
        return values == null ? null : values.get(key);
    }

    /** Indexed projection used to bootstrap clients: level -> entityId -> key -> value. */
    public Map<ConfigLevel, Map<String, Map<String, String>>> overrideIndexView() {
        return overrides;
    }

    public int overrideCount() {
        int total = 0;
        for (Map<String, Map<String, String>> byEntity : overrides.values()) {
            for (Map<String, String> values : byEntity.values()) {
                total += values.size();
            }
        }
        return total;
    }

    private static Map<ConfigLevel, Map<String, Map<String, String>>> emptyOverrideIndex() {
        Map<ConfigLevel, Map<String, Map<String, String>>> index = new EnumMap<>(ConfigLevel.class);
        for (ConfigLevel level : ConfigLevel.values()) {
            index.put(level, new HashMap<>());
        }
        return index;
    }

    private static Map<ConfigLevel, Map<String, Map<String, String>>> freeze(
            Map<ConfigLevel, Map<String, Map<String, String>>> index) {

        Map<ConfigLevel, Map<String, Map<String, String>>> frozen = new EnumMap<>(ConfigLevel.class);
        // GLOBAL never holds overrides, but keeping it preserves a uniform lookup path.
        frozen.put(ConfigLevel.GLOBAL, Map.of());

        for (Map.Entry<ConfigLevel, Map<String, Map<String, String>>> entry : index.entrySet()) {
            if (entry.getKey() == ConfigLevel.GLOBAL) {
                continue;
            }
            Map<String, Map<String, String>> byEntity = new HashMap<>(entry.getValue().size() * 2);
            entry.getValue().forEach((entityId, values) -> byEntity.put(entityId, Map.copyOf(values)));
            frozen.put(entry.getKey(), Map.copyOf(byEntity));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * Immutable projection of a definition, sized for hot reads.
     */
    public record DefinitionView(
            String key,
            ConfigType type,
            String defaultValue,
            String validationRegex,
            String allowedValues,
            Double minValue,
            Double maxValue
    ) {
        static DefinitionView from(ConfigDefinition definition) {
            return new DefinitionView(
                    definition.getKey(),
                    definition.getType(),
                    definition.getDefaultValue(),
                    definition.getValidationRegex(),
                    definition.getAllowedValues(),
                    definition.getMinValue(),
                    definition.getMaxValue()
            );
        }
    }
}
