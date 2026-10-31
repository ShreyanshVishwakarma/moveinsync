package com.moveinsync.config.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Live delta channel built on the JDK's {@link WebSocket} (no extra dependency).
 *
 * <p>Reconnects use {@link BackoffPolicy}: exponential with jitter, capped, and skipped
 * entirely once the SDK is closed. Frames are parsed off the listener thread and handed
 * to the {@link Listener}; the transport itself holds no configuration state.
 */
final class ConfigWebSocketTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigWebSocketTransport.class);

    interface Listener {
        void onConnected();

        void onDisconnected(String reason);

        void onHello(long serverVersion);

        void onDelta(Wire.Delta delta);

        void onSnapshot(Wire.Snapshot snapshot);

        void onUpToDate(long serverVersion);
    }

    private final HttpClient client;
    private final URI uri;
    private final WireCodec codec;
    private final Listener listener;
    private final BackoffPolicy backoff;
    private final ScheduledExecutorService scheduler;
    private final Duration connectTimeout;
    private final LongSupplier localVersion;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean reconnectPending = new AtomicBoolean();
    private final AtomicInteger attempt = new AtomicInteger();
    private final AtomicReference<ScheduledFuture<?>> reconnectTask = new AtomicReference<>();
    private final Object sendLock = new Object();
    private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);
    private volatile WebSocket socket;

    ConfigWebSocketTransport(HttpClient client,
                             URI uri,
                             WireCodec codec,
                             Listener listener,
                             BackoffPolicy backoff,
                             ScheduledExecutorService scheduler,
                             Duration connectTimeout,
                             LongSupplier localVersion) {
        this.client = client;
        this.uri = uri;
        this.codec = codec;
        this.listener = listener;
        this.backoff = backoff;
        this.scheduler = scheduler;
        this.connectTimeout = connectTimeout;
        this.localVersion = localVersion;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        connect();
    }

    /** Asks the server for a full snapshot. Used on a version gap or a cold start. */
    void requestSnapshot() {
        long version = localVersion.getAsLong();
        send("{\"type\":\"subscribe\",\"version\":" + version + "}");
    }

    boolean isConnected() {
        WebSocket current = socket;
        return current != null && !current.isOutputClosed();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledFuture<?> task = reconnectTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "client shutting down")
                        .orTimeout(2, TimeUnit.SECONDS)
                        .exceptionally(error -> null);
            } catch (RuntimeException ex) {
                current.abort();
            }
        }
    }

    private void connect() {
        if (!running.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        reconnectPending.set(false);
        client.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .buildAsync(uri, newListener())
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        connecting.set(false);
                        log.debug("WebSocket connect failed: {}", error.getClass().getSimpleName());
                        scheduleReconnect();
                    }
                });
    }

    private WebSocket.Listener newListener() {
        return new WebSocket.Listener() {
            private final StringBuilder partial = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                socket = webSocket;
                connecting.set(false);
                attempt.set(0);
                reconnectPending.set(false);
                log.info("SDK connected to configuration stream at {}", uri);
                listener.onConnected();
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                String message;
                synchronized (partial) {
                    partial.append(data);
                    if (!last) {
                        webSocket.request(1);
                        return null;
                    }
                    message = partial.toString();
                    partial.setLength(0);
                }
                try {
                    handle(message);
                } catch (IOException | RuntimeException ex) {
                    log.warn("Dropping malformed configuration frame: {}", ex.getClass().getSimpleName());
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                socket = null;
                connecting.set(false);
                listener.onDisconnected("closed:" + statusCode);
                scheduleReconnect();
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                socket = null;
                connecting.set(false);
                listener.onDisconnected("error:" + error.getClass().getSimpleName());
                scheduleReconnect();
            }
        };
    }

    private void handle(String message) throws IOException {
        JsonNode node;
        try {
            node = codec.readTree(message);
        } catch (Exception ex) {
            log.warn("Ignoring unreadable WebSocket frame");
            return;
        }

        String type = node.path("type").asText("");
        switch (type) {
            case "hello" -> listener.onHello(node.path("version").asLong(0));
            case "delta" -> listener.onDelta(codec.toDelta(node));
            case "snapshot" -> listener.onSnapshot(codec.toSnapshot(node.path("snapshot")));
            case "up-to-date" -> listener.onUpToDate(node.path("version").asLong(0));
            case "error" -> log.warn("Server rejected a client frame code={}", node.path("code").asText("UNKNOWN"));
            default -> log.debug("Ignoring unknown frame type {}", type);
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || !reconnectPending.compareAndSet(false, true)) {
            return;
        }
        int attemptNumber = attempt.getAndIncrement();
        Duration delay = backoff.nextDelay(attemptNumber);
        log.info("Reconnecting to configuration stream in {} ms (attempt {})", delay.toMillis(), attemptNumber + 1);

        ScheduledFuture<?> task = scheduler.schedule(() -> {
            reconnectPending.set(false);
            connect();
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = reconnectTask.getAndSet(task);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void send(String text) {
        WebSocket current = socket;
        if (current == null) {
            return;
        }
        synchronized (sendLock) {
            // The JDK allows one outstanding send at a time; chain them instead of blocking.
            sendTail = sendTail
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> current.sendText(text, true))
                    .thenApply(sent -> (Void) null)
                    .exceptionally(error -> {
                        log.debug("Could not write to configuration stream: {}", error.getClass().getSimpleName());
                        return null;
                    });
        }
    }
}
