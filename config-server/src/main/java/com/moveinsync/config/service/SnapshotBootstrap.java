package com.moveinsync.config.service;

import com.moveinsync.config.snapshot.SnapshotStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Warms the in-memory snapshot before the web server accepts traffic, so the first
 * read never hits the database.
 */
@Component
public class SnapshotBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBootstrap.class);

    private final SnapshotLoader snapshotLoader;
    private final SnapshotStore snapshotStore;

    public SnapshotBootstrap(SnapshotLoader snapshotLoader, SnapshotStore snapshotStore) {
        this.snapshotLoader = snapshotLoader;
        this.snapshotStore = snapshotStore;
    }

    @PostConstruct
    public void loadInitialSnapshot() {
        var snapshot = snapshotLoader.load();
        snapshotStore.publish(snapshot);
        log.info("Snapshot v{} loaded ({} definitions, {} overrides)",
                snapshot.version(), snapshot.definitions().size(), snapshot.overrideCount());
    }
}
