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
 * <p>Overrides carry their owning tenant, so an office override belonging to client A can
 * never be resolved by a request scoped to client B even if the office id is guessed.
 *
 * <p>Memory is O(definitions + overrides); the index duplicates override values as
 * {@code level -> entityId -> key -> (value, tenant)} so resolution never scans.
 */
public final class ConfigSnapshot {

    private static final Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> NO_OVERRIDES =
            emptyOverrideIndex();

    /** Separates tenant from entity id inside the index; never appears in real ids. */
    private static final char TENANT_SEPARATOR = '\u0001';

    /** Strict precedence probe order: most specific first. */
    private static final List<ConfigLevel> PROBE_ORDER =
            List.of(ConfigLevel.SHIFT, ConfigLevel.OFFICE, ConfigLevel.CLIENT);

    private final long version;
    private final Map<String, DefinitionView> definitions;
    private final Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> overrides;

    private ConfigSnapshot(long version,
                           Map<String, DefinitionView> definitions,
                           Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> overrides) {
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

        Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> overrideIndex = emptyOverrideIndex();
        for (ConfigOverride override : overrides) {
            String tenant = override.getClientId() != null
                    ? override.getClientId()
                    : override.getLevel() == ConfigLevel.CLIENT ? override.getEntityId() : null;
            String indexKey = indexKey(override.getLevel(), override.getEntityId(), tenant);
            overrideIndex.get(override.getLevel())
                    .computeIfAbsent(indexKey, ignored -> new HashMap<>(8))
                    .put(override.getConfigKey(), new ScopedValue(override.getValue(), tenant));
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
            String value = valueAt(level, entityId, key, context.clientId());
            if (value != null) {
                return new ResolvedValue(key, value, level, entityId);
            }
        }
        return new ResolvedValue(key, definition.defaultValue(), ConfigLevel.GLOBAL, "DEFAULT");
    }

    /**
     * Produces the full precedence trace for a key, including levels that were
     * skipped because the context had no id or the override belongs to another tenant.
     */
    public List<ResolutionStep> explain(String key, TenantContext context) {
        if (!definitions.containsKey(key)) {
            throw new IllegalArgumentException("Unknown configuration key");
        }
        List<ResolutionStep> steps = new ArrayList<>(4);
        boolean alreadyApplied = false;

        for (ConfigLevel level : PROBE_ORDER) {
            String entityId = context.entityIdFor(level);
            String value = valueAt(level, entityId, key, context.clientId());
            boolean applies = !alreadyApplied && value != null;
            steps.add(new ResolutionStep(level, entityId, value, applies));
            alreadyApplied = alreadyApplied || applies;
        }

        DefinitionView definition = definitions.get(key);
        steps.add(new ResolutionStep(ConfigLevel.GLOBAL, "DEFAULT", definition.defaultValue(), !alreadyApplied));
        return steps;
    }

    /** Tenant-aware raw override lookup. */
    public String valueAt(ConfigLevel level, String entityId, String key, String clientId) {
        if (entityId == null) {
            return null;
        }
        Map<String, ScopedValue> values = overrides.get(level).get(indexKey(level, entityId, clientId));
        if (values == null && level != ConfigLevel.CLIENT && clientId != null) {
            // Legacy/unscoped rows written before tenant tagging remain readable.
            values = overrides.get(level).get(entityId);
        }
        if (values == null) {
            return null;
        }
        ScopedValue scoped = values.get(key);
        if (scoped == null) {
            return null;
        }
        if (scoped.clientId() != null && !scoped.clientId().equals(clientId)) {
            return null;
        }
        return scoped.value();
    }

    /**
     * Overrides visible to one tenant, in the shape the snapshot API and SDK expect.
     *
     * @param clientId tenant filter; {@code null} means "everything" (platform admin)
     */
    public Map<ConfigLevel, Map<String, Map<String, String>>> overridesFor(String clientId) {
        Map<ConfigLevel, Map<String, Map<String, String>>> visible = new EnumMap<>(ConfigLevel.class);
        for (ConfigLevel level : ConfigLevel.values()) {
            if (level == ConfigLevel.GLOBAL) {
                visible.put(level, Map.of());
                continue;
            }
            Map<String, Map<String, String>> entities = new HashMap<>();
            overrides.get(level).forEach((indexKey, values) -> {
                TenantEntity decoded = decode(level, indexKey);
                if (clientId != null && decoded.tenant() != null && !decoded.tenant().equals(clientId)) {
                    return;
                }
                Map<String, String> keys = new HashMap<>();
                values.forEach((key, scoped) -> keys.put(key, scoped.value()));
                if (!keys.isEmpty()) {
                    // Scoped views use plain entity ids; the platform-admin view keeps the
                    // tenant-qualified key so two tenants with the same office id stay distinct.
                    entities.put(clientId == null ? indexKey : decoded.entity(), Map.copyOf(keys));
                }
            });
            visible.put(level, Map.copyOf(entities));
        }
        return Collections.unmodifiableMap(visible);
    }

    private static String indexKey(ConfigLevel level, String entityId, String tenant) {
        if (level == ConfigLevel.CLIENT || tenant == null) {
            return entityId;
        }
        return tenant + TENANT_SEPARATOR + entityId;
    }

    private static TenantEntity decode(ConfigLevel level, String indexKey) {
        if (level == ConfigLevel.CLIENT) {
            return new TenantEntity(indexKey, indexKey);
        }
        int separator = indexKey.indexOf(TENANT_SEPARATOR);
        if (separator < 0) {
            return new TenantEntity(indexKey, null);
        }
        return new TenantEntity(
                indexKey.substring(separator + 1),
                indexKey.substring(0, separator)
        );
    }

    private record TenantEntity(String entity, String tenant) {
    }

    /** Flattened tenant-tagged view, used by rollback state reconstruction. */
    public List<ScopedOverride> scopedEntries() {
        List<ScopedOverride> entries = new ArrayList<>();
        overrides.forEach((level, byEntity) -> {
            if (level == ConfigLevel.GLOBAL) {
                return;
            }
            byEntity.forEach((indexKey, values) -> {
                TenantEntity decoded = decode(level, indexKey);
                values.forEach((key, scoped) -> entries.add(new ScopedOverride(
                        level, key, decoded.entity(), scoped.clientId(), scoped.value())));
            });
        });
        return entries;
    }

    public record ScopedOverride(ConfigLevel level, String key, String entityId, String clientId, String value) {
    }

    public int overrideCount() {        int total = 0;
        for (Map<String, Map<String, ScopedValue>> byEntity : overrides.values()) {
            for (Map<String, ScopedValue> values : byEntity.values()) {
                total += values.size();
            }
        }
        return total;
    }

    private static Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> emptyOverrideIndex() {
        Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> index = new EnumMap<>(ConfigLevel.class);
        for (ConfigLevel level : ConfigLevel.values()) {
            index.put(level, new HashMap<>());
        }
        return index;
    }

    private static Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> freeze(
            Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> index) {

        Map<ConfigLevel, Map<String, Map<String, ScopedValue>>> frozen = new EnumMap<>(ConfigLevel.class);
        // GLOBAL never holds overrides, but keeping it preserves a uniform lookup path.
        frozen.put(ConfigLevel.GLOBAL, Map.of());

        for (Map.Entry<ConfigLevel, Map<String, Map<String, ScopedValue>>> entry : index.entrySet()) {
            if (entry.getKey() == ConfigLevel.GLOBAL) {
                continue;
            }
            Map<String, Map<String, ScopedValue>> byEntity = new HashMap<>(entry.getValue().size() * 2);
            entry.getValue().forEach((entityId, values) -> byEntity.put(entityId, Map.copyOf(values)));
            frozen.put(entry.getKey(), Map.copyOf(byEntity));
        }
        return Collections.unmodifiableMap(frozen);
    }

    /**
     * An override value plus the tenant that owns it.
     */
    public record ScopedValue(String value, String clientId) {
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
