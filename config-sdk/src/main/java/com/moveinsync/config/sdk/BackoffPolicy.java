package com.moveinsync.config.sdk;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Jittered exponential backoff for reconnects.
 *
 * <p>{@code wait = min(max, base * 2^attempt) + random(0, jitter)}. The jitter term is
 * what stops 500 crashed services from reconnecting in lockstep and hammering a freshly
 * restarted server.
 */
public final class BackoffPolicy {

    private final Duration base;
    private final Duration max;
    private final Duration jitter;
    private final RandomGenerator random;

    public BackoffPolicy(Duration base, Duration max, Duration jitter, RandomGenerator random) {
        this.base = base;
        this.max = max;
        this.jitter = jitter;
        this.random = random;
    }

    public static BackoffPolicy defaults() {
        return new BackoffPolicy(
                Duration.ofMillis(500),
                Duration.ofSeconds(30),
                Duration.ofMillis(500),
                RandomGenerator.getDefault()
        );
    }

    /**
     * @param attempt zero-based attempt counter; values above 30 are clamped so the
     *                exponential cannot overflow
     */
    public Duration nextDelay(int attempt) {
        int exponent = Math.min(Math.max(attempt, 0), 30);
        long exponentialMs = base.toMillis() * (1L << exponent);
        long capped = Math.min(max.toMillis(), exponentialMs);
        long jitterMs = jitter.isZero() ? 0 : random.nextLong(jitter.toMillis() + 1);
        return Duration.ofMillis(capped + jitterMs);
    }

    public Duration base() {
        return base;
    }

    public Duration max() {
        return max;
    }

    public Duration jitter() {
        return jitter;
    }
}
