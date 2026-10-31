package com.moveinsync.config.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * JSON boundary. Kept separate so the rest of the SDK never depends on Jackson types.
 */
final class WireCodec {

    private final ObjectMapper mapper;

    WireCodec() {
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    JsonNode readTree(String json) throws IOException {
        return mapper.readTree(json);
    }

    Wire.Snapshot toSnapshot(JsonNode node) throws IOException {
        return mapper.treeToValue(node, Wire.Snapshot.class);
    }

    Wire.Snapshot readSnapshot(String json) throws IOException {
        return mapper.readValue(json, Wire.Snapshot.class);
    }

    Wire.Delta toDelta(JsonNode node) throws IOException {
        return mapper.treeToValue(node, Wire.Delta.class);
    }

    Wire.SyncResponse toSyncResponse(String json) throws IOException {
        return mapper.readValue(json, Wire.SyncResponse.class);
    }

    String write(Object value) throws IOException {
        return mapper.writeValueAsString(value);
    }
}
