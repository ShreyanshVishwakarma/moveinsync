package com.moveinsync.config.ws;

import com.moveinsync.config.metrics.ConfigMetrics;
import com.moveinsync.config.service.ConfigPublishedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Bridges publication events to WebSocket fan-out.
 *
 * <p>Runs on the dedicated single-thread executor: a commit never waits for sockets,
 * and frames leave in version order even when change sets are published back to back.
 * Kept as its own bean so the handler itself is never wrapped in an async proxy.
 */
@Component
public class ConfigBroadcastListener {

    private static final Logger log = LoggerFactory.getLogger(ConfigBroadcastListener.class);

    private final ConfigWebSocketHandler handler;
    private final ConfigMetrics metrics;

    public ConfigBroadcastListener(ConfigWebSocketHandler handler, ConfigMetrics metrics) {
        this.handler = handler;
        this.metrics = metrics;
    }

    @Async("broadcastExecutor")
    @EventListener
    public void onConfigPublished(ConfigPublishedEvent event) {
        Map<String, Object> delta = Map.of(
                "type", "delta",
                "version", event.version(),
                "entries", event.entries()
        );

        metrics.broadcastQueued(0);
        int sent = handler.broadcast(delta);
        if (sent > 0) {
            log.debug("Broadcast v{} to {} clients ({} changes)",
                    event.version(), sent, event.entries().size());
        }
    }
}
