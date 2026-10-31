package com.moveinsync.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.metrics.ConfigMetrics;
import com.moveinsync.config.snapshot.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Push channel for configuration deltas.
 *
 * <p>Clients connect, receive a {@code hello} frame with the current version, and then
 * either receive deltas or ask for a snapshot when they detect a version gap. Sends are
 * performed on a single background thread so a slow consumer cannot block a commit, and
 * every session is wrapped in a concurrent decorator to make writes thread-safe.
 */
@Component
public class ConfigWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfigWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final SnapshotStore snapshotStore;
    private final ConfigMetrics metrics;
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final int sendTimeLimitMs;

    public ConfigWebSocketHandler(ObjectMapper objectMapper,
                                  SnapshotStore snapshotStore,
                                  ConfigMetrics metrics,
                                  @Value("${moveinsync.config.websocket.send-timeout-ms:5000}") int sendTimeLimitMs) {
        this.objectMapper = objectMapper;
        this.snapshotStore = snapshotStore;
        this.metrics = metrics;
        this.sendTimeLimitMs = sendTimeLimitMs;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(session, sendTimeLimitMs, 512 * 1024);
        sessions.add(safeSession);
        metrics.clientConnected();
        log.debug("WebSocket connected, clients={}", sessions.size());

        send(safeSession, Map.of(
                "type", "hello",
                "version", snapshotStore.version(),
                "heartbeatMs", 10_000
        ));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        ClientFrame frame;
        try {
            frame = objectMapper.readValue(message.getPayload(), ClientFrame.class);
        } catch (IOException ex) {
            send(session, Map.of("type", "error", "code", "BAD_FRAME"));
            return;
        }

        if (!"subscribe".equals(frame.type())) {
            send(session, Map.of("type", "error", "code", "UNKNOWN_FRAME"));
            return;
        }

        long clientVersion = frame.version() == null ? 0L : frame.version();
        long serverVersion = snapshotStore.version();

        if (clientVersion >= serverVersion) {
            send(session, Map.of("type", "up-to-date", "version", serverVersion));
            return;
        }

        // Gap or cold start: hand over the full immutable snapshot. The SDK swaps it in
        // with a single atomic reference write.
        send(session, Map.of(
                "type", "snapshot",
                "version", serverVersion,
                "snapshot", SnapshotResponse.from(snapshotStore.current())
        ));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        metrics.clientDisconnected();
        log.debug("WebSocket closed status={} clients={}", status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error", exception);
        metrics.recordBroadcastFailure();
        sessions.remove(session);
    }

    /**
     * Fan-out helper used by {@link ConfigBroadcastListener}. Callers run on the
     * single broadcast thread, which preserves version order across rapid publications.
     */
    public int broadcast(Object frame) {
        int sent = 0;
        for (WebSocketSession session : sessions) {
            try {
                send(session, frame);
                sent++;
            } catch (IOException ex) {
                metrics.recordBroadcastFailure();
                closeQuietly(session);
            }
        }
        metrics.recordBroadcast(sent);
        return sent;
    }

    public int sessionCount() {
        return sessions.size();
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        String json = objectMapper.writeValueAsString(payload);
        session.sendMessage(new TextMessage(json));
    }

    private void closeQuietly(WebSocketSession session) {
        sessions.remove(session);
        metrics.clientDisconnected();
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
            // already gone
        }
    }

    /** Inbound frame from the SDK. */
    record ClientFrame(String type, Long version) {
    }
}
