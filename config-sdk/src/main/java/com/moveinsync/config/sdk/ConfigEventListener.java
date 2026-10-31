package com.moveinsync.config.sdk;

/**
 * Optional hooks for host applications. All methods have no-op defaults, so callers
 * implement only what they need.
 *
 * <p>Callbacks may run on transport threads; keep them fast and non-blocking. Reasons
 * passed here are stable codes, never payloads or personal data.
 */
public interface ConfigEventListener {

    default void onUpdate(long version, int overrideCount) {
    }

    default void onGapDetected(long expectedVersion, long receivedVersion) {
    }

    default void onSyncFailure(String reason) {
    }

    default void onConnectionStateChanged(boolean connected) {
    }
}
