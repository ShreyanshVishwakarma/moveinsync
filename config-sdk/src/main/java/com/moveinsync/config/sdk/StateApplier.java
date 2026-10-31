package com.moveinsync.config.sdk;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns every transition of the in-memory state and enforces the version rules in one
 * place. All mutations are serialised on this object; readers never lock.
 *
 * <p>Rules (from the case study):
 * <ul>
 *   <li>{@code incoming == current + 1} → apply</li>
 *   <li>{@code incoming <= current} → ignore (duplicate or stale)</li>
 *   <li>{@code incoming > current + 1} → gap, state untouched, caller must resync</li>
 * </ul>
 */
public final class StateApplier {

    public enum Outcome {
        APPLIED,
        IGNORED_DUPLICATE,
        IGNORED_STALE,
        GAP_DETECTED
    }

    public record Result(Outcome outcome, ConfigState state, long expectedVersion, long receivedVersion) {
        public boolean changed() {
            return outcome == Outcome.APPLIED;
        }
    }

    private final AtomicReference<ConfigState> state = new AtomicReference<>(ConfigState.empty());

    /** Seeds the state from disk cache without ever moving the version backwards. */
    public synchronized boolean initialize(ConfigState cached) {
        ConfigState current = state.get();
        if (cached == null || cached.version() < current.version()) {
            return false;
        }
        state.set(cached);
        return true;
    }

    public ConfigState current() {
        return state.get();
    }

    public synchronized Result applyDelta(Wire.Delta delta) {
        ConfigState current = state.get();
        long expected = current.version() + 1;

        if (delta.version() == current.version()) {
            return new Result(Outcome.IGNORED_DUPLICATE, current, expected, delta.version());
        }
        if (delta.version() < current.version()) {
            return new Result(Outcome.IGNORED_STALE, current, expected, delta.version());
        }
        if (delta.version() > expected) {
            return new Result(Outcome.GAP_DETECTED, current, expected, delta.version());
        }

        ConfigState next = current.applyDelta(delta);
        state.set(next);
        return new Result(Outcome.APPLIED, next, expected, delta.version());
    }

    /**
     * Replaces the whole state from a snapshot. A snapshot at the same version is
     * accepted only when the local state is still empty (cold start), and older
     * snapshots are always ignored so the version never regresses.
     */
    public synchronized Result replaceSnapshot(Wire.Snapshot snapshot) {
        ConfigState current = state.get();
        ConfigState candidate = ConfigState.fromSnapshot(snapshot);
        long expected = current.version() + 1;

        if (candidate.version() < current.version()) {
            return new Result(Outcome.IGNORED_STALE, current, expected, candidate.version());
        }
        if (candidate.version() == current.version() && !current.definitions().isEmpty()) {
            return new Result(Outcome.IGNORED_DUPLICATE, current, expected, candidate.version());
        }

        state.set(candidate);
        return new Result(Outcome.APPLIED, candidate, expected, candidate.version());
    }
}
