package com.moveinsync.config.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.config.dto.ChangeEntryResponse;
import com.moveinsync.config.dto.SnapshotResponse;
import com.moveinsync.config.metrics.ConfigMetrics;
import com.moveinsync.config.security.Actor;
import com.moveinsync.config.security.WebSocketAuthInterceptor;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Push channel for configuration deltas.
 *
 * <p>Clients authenticate during the HTTP upgrade; the resulting {@link Actor} is stored
 * on the session and used to filter every frame. A client therefore never receives another
 * tenant's override, even though all connections share one broadcast loop.
 *
 * <p>Sends run on a single background thread so a slow consumer cannot block a commit, and
 * each session is wrapped in a concurrent decorator to make writes thread-safe.
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
        Actor actor = actorOf(session);
        if (actor == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
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
        Actor actor = actorOf(session);
        if (actor == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

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

        // Gap or cold start: hand over the full immutable snapshot, filtered to this tenant.
        send(session, Map.of(
                "type", "snapshot",
                "version", serverVersion,
                "snapshot", SnapshotResponse.from(snapshotStore.current(), tenantOf(actor))
        ));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (sessions.remove(session)) {
            metrics.clientDisconnected();
        }
        log.debug("WebSocket closed status={} clients={}", status, sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error", exception);
        metrics.recordBroadcastFailure();
        if (sessions.remove(session)) {
            metrics.clientDisconnected();
        }
    }

    /**
     * Fans a delta out to every session, filtering entries per tenant. The frame is sent
     * even when no entry is visible, so clients still advance their version instead of
     * triggering an unnecessary resync on the next change.
     */
    public int broadcast(long version, List<ChangeEntryResponse> entries) {
        int sent = 0;
        for (WebSocketSession session : sessions) {
            Actor actor = actorOf(session);
            if (actor == null) {
                continue;
            }
            List<ChangeEntryResponse> visible = entries.stream()
                    .filter(entry -> visibleTo(actor, entry))
                    .toList();
            Map<String, Object> delta = Map.of(
                    "type", "delta",
                    "version", version,
                    "entries", visible
            );
            try {
                send(session, delta);
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

    private boolean visibleTo(Actor actor, ChangeEntryResponse entry) {
        return actor.isAdmin() || entry.clientId() == null || entry.clientId().equals(actor.clientId());
    }

    private String tenantOf(Actor actor) {
        return actor.isAdmin() ? null : actor.clientId();
    }

    private Actor actorOf(WebSocketSession session) {
        Object value = session.getAttributes().get(WebSocketAuthInterceptor.ACTOR_ATTRIBUTE);
        return value instanceof Actor actor ? actor : null;
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        String json = objectMapper.writeValueAsString(payload);
        session.sendMessage(new TextMessage(json));
    }

    private void closeQuietly(WebSocketSession session) {
        if (sessions.remove(session)) {
            metrics.clientDisconnected();
        }
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
