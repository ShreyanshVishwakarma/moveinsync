package com.moveinsync.config.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Version check and full-snapshot fetch. Deliberately tiny: the SDK's hot path never
 * touches HTTP, this is only the self-healing path.
 */
final class ConfigHttpTransport {

    private final HttpClient client;
    private final String baseUrl;
    private final Duration timeout;
    private final String apiKey;
    private final WireCodec codec = new WireCodec();

    ConfigHttpTransport(HttpClient client, String baseUrl, Duration timeout, String apiKey) {
        this.client = client;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.apiKey = apiKey;
    }

    Optional<Long> fetchVersion() {
        try {
            HttpResponse<String> response = client.send(request("/api/v1/config/version"), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            JsonNode node = codec.readTree(response.body());
            return Optional.of(node.path("version").asLong(0));
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    Optional<Wire.Snapshot> fetchSnapshot() {
        try {
            HttpResponse<String> response = client.send(request("/api/v1/config/snapshot"), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return Optional.of(codec.readSnapshot(response.body()));
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private HttpRequest request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }
        return builder.build();
    }
}
