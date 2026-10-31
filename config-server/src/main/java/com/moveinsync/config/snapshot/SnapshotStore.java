package com.moveinsync.config.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Holds the current {@link ConfigSnapshot} behind a single {@link AtomicReference}.
 *
 * <p>This is the server-side twin of the client SDK's in-memory map: readers call
 * {@link #current()} and get a consistent view with no locking, while writers build a
 * new snapshot and publish it with one atomic {@code set}. Because snapshots are
 * immutable, a reader can never observe half of a change set.
 */
@Component
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);

    private final AtomicReference<ConfigSnapshot> current = new AtomicReference<>(ConfigSnapshot.empty());
    private final List<Consumer<ConfigSnapshot>> listeners = new CopyOnWriteArrayList<>();

    public ConfigSnapshot current() {
        return current.get();
    }

    public long version() {
        return current.get().version();
    }

    /**
     * Atomically replaces the snapshot and notifies listeners. Publishing an older
     * version than the current one is ignored, so version order can never regress.
     *
     * @return true when the snapshot was published
     */
    public boolean publish(ConfigSnapshot snapshot) {
        ConfigSnapshot previous;
        do {
            previous = current.get();
            if (snapshot.version() < previous.version()) {
                log.warn("Ignoring snapshot v{} older than current v{}", snapshot.version(), previous.version());
                return false;
            }
        } while (!current.compareAndSet(previous, snapshot));

        listeners.forEach(listener -> {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ex) {
                log.warn("Snapshot listener failed", ex);
            }
        });
        return true;
    }

    public void onPublish(Consumer<ConfigSnapshot> listener) {
        listeners.add(listener);
    }

    public void clearListeners() {
        listeners.clear();
    }
}
