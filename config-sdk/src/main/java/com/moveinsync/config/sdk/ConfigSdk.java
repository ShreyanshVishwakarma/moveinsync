package com.moveinsync.config.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Embedded configuration client.
 *
 * <pre>{@code
 * try (ConfigSdk sdk = ConfigSdk.builder()
 *         .baseUrl("http://config-server:8080")
 *         .clientId("acme-corp")
 *         .officeId("blr-office-1")
 *         .build()) {
 *     sdk.start();
 *     int cutoff = sdk.getInt("booking_cutoff_minutes");   // microseconds, no network
 * }
 * }</pre>
 *
 * <p>Reads resolve against an immutable {@link ConfigState} behind one
 * {@code AtomicReference}: no locks, no I/O, no partial change sets. Deltas arrive over
 * WebSocket, a periodic version poll heals missed pushes, and a disk cache covers a cold
 * start while the server is down.
 */
public final class ConfigSdk implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigSdk.class);

    private final String baseUrl;
    private final TenantContext defaultTenant;
    private final Duration pollInterval;
    private final ConfigEventListener eventListener;

    private final WireCodec codec = new WireCodec();
    private final StateApplier applier = new StateApplier();
    private final HttpClient httpClient;
    private final ConfigHttpTransport http;
    private final ConfigWebSocketTransport webSocket;
    private final SnapshotDiskCache cache;
    private final ScheduledExecutorService scheduler;
    private final BackoffPolicy backoff;

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicLong deltasApplied = new AtomicLong();
    private final AtomicLong staleIgnored = new AtomicLong();
    private final AtomicLong gapsDetected = new AtomicLong();
    private final AtomicLong resyncs = new AtomicLong();
    private final AtomicLong syncFailures = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicReference<Instant> lastSyncAt = new AtomicReference<>();
    private volatile boolean connected;

    private ConfigSdk(Builder builder) {
        this.baseUrl = normalize(builder.baseUrl);
        this.defaultTenant = new TenantContext(builder.clientId, builder.officeId, builder.shiftId);
        this.pollInterval = builder.pollInterval;
        this.eventListener = builder.eventListener;
        this.backoff = builder.backoff;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.http = new ConfigHttpTransport(httpClient, baseUrl, builder.requestTimeout);

        this.scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "config-sdk");
            thread.setDaemon(true);
            return thread;
        });

        this.webSocket = new ConfigWebSocketTransport(
                httpClient,
                URI.create(webSocketUrl(baseUrl, builder.websocketPath)),
                codec,
                new WebSocketListener(),
                backoff,
                scheduler,
                builder.connectTimeout,
                () -> applier.current().version()
        );

        this.cache = builder.cacheFile != null ? new SnapshotDiskCache(builder.cacheFile) : null;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Loads the disk snapshot (if any), starts the version poll and opens the live stream.
     * Idempotent; safe to call from a bean initialiser.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (cache != null) {
            cache.load().ifPresent(snapshot -> {
                if (applier.initialize(ConfigState.fromSnapshot(snapshot))) {
                    lastSyncAt.set(Instant.now());
                    log.info("Restored cached configuration v{} from {}", snapshot.version(), cache.file());
                    eventListener.onUpdate(snapshot.version(), applier.current().overrideCount());
                }
            });
        }
        scheduler.scheduleWithFixedDelay(this::pollQuietly,
                0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
        webSocket.start();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        webSocket.close();
        scheduler.shutdownNow();
        if (cache != null) {
            cache.close();
        }
        connected = false;
    }

    // ------------------------------------------------------------------------ reads

    /** Value for the default tenant. Throws when the key is unknown. */
    public String get(String key) {
        return resolve(key, defaultTenant).value();
    }

    /** Value for an explicit tenant context (per-request office or shift). */
    public String get(String key, TenantContext context) {
        return resolve(key, context).value();
    }

    public Optional<String> find(String key) {
        ConfigState state = applier.current();
        if (!state.hasDefinition(key)) {
            return Optional.empty();
        }
        return Optional.of(state.resolve(key, defaultTenant).value());
    }

    public int getInt(String key) {
        return parseInt(key, get(key));
    }

    public long getLong(String key) {
        try {
            return Long.parseLong(get(key).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + ": value is not a valid long");
        }
    }

    public double getDouble(String key) {
        try {
            return Double.parseDouble(get(key).trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + ": value is not a valid number");
        }
    }

    public boolean getBoolean(String key) {
        String value = get(key).trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalStateException(key + ": value is not a boolean");
    }

    /** Durations are stored as whole minutes, matching the server's DURATION type. */
    public Duration getDuration(String key) {
        try {
            return Duration.ofMinutes(Long.parseLong(get(key).trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + ": value is not a duration in minutes");
        }
    }

    public List<String> getList(String key) {
        return List.of(get(key).split("\\s*,\\s*"));
    }

    public ResolvedValue resolve(String key, TenantContext context) {
        return applier.current().resolve(key, context);
    }

    public List<ResolutionStep> explain(String key, TenantContext context) {
        return applier.current().explain(key, context);
    }

    public long version() {
        return applier.current().version();
    }

    /** Immutable current state; useful for diagnostics and custom consumers. */
    public ConfigState state() {
        return applier.current();
    }

    public boolean isConnected() {
        return connected && webSocket.isConnected();
    }

    public SdkStats stats() {
        ConfigState state = applier.current();
        return new SdkStats(
                state.version(),
                state.definitions().size(),
                state.overrideCount(),
                deltasApplied.get(),
                staleIgnored.get(),
                gapsDetected.get(),
                resyncs.get(),
                syncFailures.get(),
                reconnects.get(),
                connected,
                lastSyncAt.get()
        );
    }

    // --------------------------------------------------------------- sync internals

    private void pollQuietly() {
        if (closed.get()) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException ex) {
            recordSyncFailure("poll-error");
            log.debug("Version poll failed: {}", ex.getClass().getSimpleName());
        }
    }

    private void pollOnce() {
        Optional<Long> serverVersion = http.fetchVersion();
        if (serverVersion.isEmpty()) {
            recordSyncFailure("version-unreachable");
            return;
        }

        ConfigState current = applier.current();
        boolean needsBootstrap = current.definitions().isEmpty();
        if (serverVersion.get() <= current.version() && !needsBootstrap) {
            return;
        }

        Optional<Wire.Snapshot> snapshot = http.fetchSnapshot();
        if (snapshot.isEmpty()) {
            recordSyncFailure("snapshot-unreachable");
            return;
        }
        applySnapshot(snapshot.get());
    }

    private void applyDelta(Wire.Delta delta) {
        StateApplier.Result result = applier.applyDelta(delta);
        switch (result.outcome()) {
            case APPLIED -> {
                deltasApplied.incrementAndGet();
                lastSyncAt.set(Instant.now());
                publish(result.state());
            }
            case IGNORED_DUPLICATE, IGNORED_STALE -> {
                staleIgnored.incrementAndGet();
                log.debug("Ignored configuration v{} (current v{})", delta.version(), result.state().version());
            }
            case GAP_DETECTED -> {
                gapsDetected.incrementAndGet();
                eventListener.onGapDetected(result.expectedVersion(), result.receivedVersion());
                log.info("Version gap: expected v{} but received v{}; requesting snapshot",
                        result.expectedVersion(), result.receivedVersion());
                webSocket.requestSnapshot();
            }
        }
    }

    private void applySnapshot(Wire.Snapshot snapshot) {
        StateApplier.Result result = applier.replaceSnapshot(snapshot);
        switch (result.outcome()) {
            case APPLIED -> {
                resyncs.incrementAndGet();
                lastSyncAt.set(Instant.now());
                log.info("Synchronised configuration v{} ({} definitions, {} overrides)",
                        result.state().version(),
                        result.state().definitions().size(),
                        result.state().overrideCount());
                publish(result.state());
            }
            case IGNORED_DUPLICATE, IGNORED_STALE -> {
                staleIgnored.incrementAndGet();
                log.debug("Ignored snapshot v{} (current v{})", snapshot.version(), result.state().version());
            }
            case GAP_DETECTED -> {
                // Snapshots carry absolute state, so this cannot happen; keep the switch
                // exhaustive without inventing behaviour.
            }
        }
    }

    private void publish(ConfigState state) {
        if (cache != null) {
            cache.save(state.toSnapshot());
        }
        eventListener.onUpdate(state.version(), state.overrideCount());
    }

    private void recordSyncFailure(String reason) {
        syncFailures.incrementAndGet();
        eventListener.onSyncFailure(reason);
    }

    private int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException(key + ": value is not a valid integer");
        }
    }

    /** Bridges transport callbacks into state transitions and health counters. */
    private final class WebSocketListener implements ConfigWebSocketTransport.Listener {

        @Override
        public void onConnected() {
            connected = true;
            eventListener.onConnectionStateChanged(true);
        }

        @Override
        public void onDisconnected(String reason) {
            connected = false;
            reconnects.incrementAndGet();
            eventListener.onConnectionStateChanged(false);
            log.info("Configuration stream disconnected ({})", reason);
        }

        @Override
        public void onHello(long serverVersion) {
            long localVersion = applier.current().version();
            boolean needsBootstrap = applier.current().definitions().isEmpty();
            if (serverVersion > localVersion || needsBootstrap) {
                webSocket.requestSnapshot();
            }
        }

        @Override
        public void onDelta(Wire.Delta delta) {
            applyDelta(delta);
        }

        @Override
        public void onSnapshot(Wire.Snapshot snapshot) {
            applySnapshot(snapshot);
        }

        @Override
        public void onUpToDate(long serverVersion) {
            lastSyncAt.set(Instant.now());
            log.debug("Already at configuration v{}", serverVersion);
        }
    }

    // ---------------------------------------------------------------------- builder

    public static final class Builder {

        private String baseUrl;
        private String clientId;
        private String officeId;
        private String shiftId;
        private String websocketPath = "/ws/config";
        private Path cacheFile = Path.of("config_cache.json");
        private Duration pollInterval = Duration.ofSeconds(3);
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private Duration backoffBase = Duration.ofMillis(500);
        private Duration backoffMax = Duration.ofSeconds(30);
        private Duration backoffJitter = Duration.ofMillis(500);
        private ConfigEventListener eventListener = new ConfigEventListener() {
        };
        private BackoffPolicy backoff;

        private Builder() {
        }

        /** Required. Example: {@code http://localhost:8080}. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder officeId(String officeId) {
            this.officeId = officeId;
            return this;
        }

        public Builder shiftId(String shiftId) {
            this.shiftId = shiftId;
            return this;
        }

        public Builder websocketPath(String websocketPath) {
            this.websocketPath = websocketPath;
            return this;
        }

        /** Where the fallback snapshot lives. Pass {@code null} to disable disk caching. */
        public Builder cacheFile(Path cacheFile) {
            this.cacheFile = cacheFile;
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder backoff(Duration base, Duration max, Duration jitter) {
            this.backoffBase = base;
            this.backoffMax = max;
            this.backoffJitter = jitter;
            return this;
        }

        public Builder eventListener(ConfigEventListener eventListener) {
            this.eventListener = eventListener != null ? eventListener : new ConfigEventListener() {
            };
            return this;
        }

        public ConfigSdk build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            if (pollInterval.isNegative() || pollInterval.isZero()) {
                throw new IllegalArgumentException("pollInterval must be positive");
            }
            if (backoff == null) {
                backoff = new BackoffPolicy(backoffBase, backoffMax, backoffJitter,
                        java.util.random.RandomGenerator.getDefault());
            }
            return new ConfigSdk(this);
        }
    }

    private static String normalize(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String webSocketUrl(String baseUrl, String path) {
        String ws = baseUrl.startsWith("https://")
                ? "wss://" + baseUrl.substring("https://".length())
                : baseUrl.startsWith("http://")
                ? "ws://" + baseUrl.substring("http://".length())
                : baseUrl;
        return ws + (path.startsWith("/") ? path : "/" + path);
    }
}
