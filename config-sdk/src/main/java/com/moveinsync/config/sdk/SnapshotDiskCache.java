package com.moveinsync.config.sdk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Disk snapshot used for two things: a cold start when the server is unreachable, and a
 * safety net across process restarts.
 *
 * <p>Writes are asynchronous and coalescing (only the newest state is persisted), and
 * always land via a temp file plus an atomic move so a crash mid-write can never leave a
 * half-written cache behind.
 */
public final class SnapshotDiskCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotDiskCache.class);

    private final Path file;
    private final WireCodec codec = new WireCodec();
    private final ExecutorService writer;
    private final AtomicReference<Wire.Snapshot> pending = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public SnapshotDiskCache(Path file) {
        this.file = file;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "config-sdk-cache");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Reads the last persisted snapshot, tolerating a missing or corrupt file. */
    public Optional<Wire.Snapshot> load() {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(codec.readSnapshot(json));
        } catch (IOException | RuntimeException ex) {
            log.warn("Ignoring unreadable configuration cache at {}: {}", file, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void save(Wire.Snapshot snapshot) {
        if (snapshot == null || closed.get()) {
            return;
        }
        pending.set(snapshot);
        scheduleWrite();
    }

    /** Blocks briefly until the newest snapshot is on disk. Used by tests and shutdown. */
    public void flush() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((pending.get() != null || scheduled.get()) && System.nanoTime() < deadline) {
            try {
                Future<?> barrier = writer.submit(() -> {
                });
                barrier.get(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                return;
            }
            if (closed.get() && pending.get() == null) {
                return;
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        flush();
        writer.shutdown();
    }

    public Path file() {
        return file;
    }

    private void scheduleWrite() {
        if (scheduled.compareAndSet(false, true)) {
            writer.execute(this::drain);
        }
    }

    private void drain() {
        scheduled.set(false);
        Wire.Snapshot snapshot = pending.getAndSet(null);
        if (snapshot == null) {
            return;
        }
        try {
            writeAtomically(snapshot);
        } catch (IOException ex) {
            log.warn("Could not persist configuration cache to {}", file);
        } finally {
            if (pending.get() != null) {
                scheduleWrite();
            }
        }
    }

    private void writeAtomically(Wire.Snapshot snapshot) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, codec.write(snapshot), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
