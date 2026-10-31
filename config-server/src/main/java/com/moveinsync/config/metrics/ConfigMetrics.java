package com.moveinsync.config.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The counters an on-call engineer needs at 3 AM: what is being read, what is being
 * rejected, how far behind clients are and how many sockets are attached.
 */
@Component
public class ConfigMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger connectedClients = new AtomicInteger();
    private final AtomicInteger pendingBroadcasts = new AtomicInteger();

    private final Counter commits;
    private final Counter rejections;
    private final Counter rollbacks;
    private final Counter resyncRequests;
    private final Counter websocketBroadcasts;
    private final Counter websocketFailures;

    public ConfigMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.commits = Counter.builder("config_commits_total")
                .description("Change sets committed successfully")
                .register(registry);
        this.rejections = Counter.builder("config_rejections_total")
                .description("Change sets rejected before publication")
                .register(registry);
        this.rollbacks = Counter.builder("config_rollbacks_total")
                .description("Rollbacks published")
                .register(registry);
        this.resyncRequests = Counter.builder("config_resync_requests_total")
                .description("Clients that asked for a snapshot/delta sync")
                .register(registry);
        this.websocketBroadcasts = Counter.builder("config_ws_broadcasts_total")
                .description("Delta frames written to WebSocket sessions")
                .register(registry);
        this.websocketFailures = Counter.builder("config_ws_failures_total")
                .description("Failed WebSocket writes")
                .register(registry);

        Gauge.builder("config_ws_connected_clients", connectedClients, AtomicInteger::get)
                .description("Currently connected WebSocket clients")
                .register(registry);
        Gauge.builder("config_ws_pending_broadcasts", pendingBroadcasts, AtomicInteger::get)
                .description("Frames queued for broadcast")
                .register(registry);
    }

    public void recordCommit() {
        commits.increment();
    }

    public void recordRejection() {
        rejections.increment();
    }

    public void recordRollback() {
        rollbacks.increment();
    }

    public void recordResync() {
        resyncRequests.increment();
    }

    public void recordBroadcast(int targets) {
        websocketBroadcasts.increment(Math.max(targets, 0));
    }

    public void recordBroadcastFailure() {
        websocketFailures.increment();
    }

    public Counter commits() {
        return commits;
    }

    public Counter rejections() {
        return rejections;
    }

    public void clientConnected() {
        connectedClients.incrementAndGet();
    }

    public void clientDisconnected() {
        connectedClients.updateAndGet(current -> Math.max(0, current - 1));
    }

    public void broadcastQueued(int delta) {
        pendingBroadcasts.updateAndGet(current -> Math.max(0, current + delta));
    }

    public MeterRegistry registry() {
        return registry;
    }
}
