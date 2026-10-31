package com.moveinsync.config.sdk;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Wire shapes shared by the REST endpoints and the WebSocket frames.
 *
 * <p>All records ignore unknown fields so the server can evolve without breaking older
 * SDKs. Values travel as strings; the definition's {@code type} decides interpretation.
 */
public final class Wire {

    private Wire() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Definition(
            String key,
            String type,
            String defaultValue,
            String validationRegex,
            String allowedValues,
            Double minValue,
            Double maxValue
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(
            long version,
            List<Definition> definitions,
            Map<String, Map<String, Map<String, String>>> overrides
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
            String key,
            String level,
            String entityId,
            String oldValue,
            String newValue
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Delta(
            long version,
            List<Entry> entries
    ) {
    }

    /** One change set inside a {@code /config/sync} response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangeSet(
            long version,
            List<Entry> entries
    ) {
    }

    /**
     * {@code GET /api/v1/config/sync?since=N}: either a full snapshot or the change sets
     * published after N.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SyncResponse(
            long version,
            long fromVersion,
            boolean full,
            Snapshot snapshot,
            List<ChangeSet> changeSets
    ) {
    }
}
