package com.moveinsync.config.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serialises publications.
 *
 * <p>Why a single lock: the version is the change-set primary key, so two concurrent
 * commits could otherwise be assigned ids in a different order than they are applied
 * to overrides. That would make the delta stream incoherent for clients. One writer
 * per cluster is a deliberate, documented trade-off; readers never touch this lock.
 */
@Component
public class CommitGate {

    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public boolean isBusy() {
        return lock.isLocked();
    }
}
