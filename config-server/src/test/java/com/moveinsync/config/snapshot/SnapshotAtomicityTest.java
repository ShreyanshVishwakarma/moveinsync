package com.moveinsync.config.snapshot;

import com.moveinsync.config.domain.ConfigDefinition;
import com.moveinsync.config.domain.ConfigOverride;
import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.domain.ConfigType;
import com.moveinsync.config.domain.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proof of the hardest rule: a change set that touches two keys is never observed
 * half-applied, no matter how many readers hammer the snapshot during the swap.
 */
class SnapshotAtomicityTest {

    private static final String START_KEY = "night_window_start";
    private static final String END_KEY = "night_window_end";
    private static final TenantContext TENANT = new TenantContext("acme-corp", null, null);

    private final SnapshotStore store = new SnapshotStore();
    private final ExecutorService pool = Executors.newFixedThreadPool(5);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void readersNeverSeeANewStartWithAnOldEnd() throws Exception {
        store.publish(snapshot(1, "22:00", "23:00"));

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong reads = new AtomicLong();
        AtomicLong mixedStates = new AtomicLong();

        CountDownLatch gate = new CountDownLatch(1);

        for (int reader = 0; reader < 4; reader++) {
            pool.submit(() -> {
                gate.await();
                while (running.get()) {
                    // One snapshot reference per read: exactly what the client SDK does.
                    ConfigSnapshot view = store.current();
                    String start = view.resolve(START_KEY, TENANT).value();
                    String end = view.resolve(END_KEY, TENANT).value();

                    boolean pairA = "22:00".equals(start) && "23:00".equals(end);
                    boolean pairB = "23:30".equals(start) && "23:45".equals(end);
                    if (!pairA && !pairB) {
                        mixedStates.incrementAndGet();
                    }
                    reads.incrementAndGet();
                }
                return null;
            });
        }

        gate.countDown();

        // Writer flips between two consistent change sets as fast as it can.
        long version = 2;
        for (int i = 0; i < 2_000; i++) {
            store.publish(i % 2 == 0
                    ? snapshot(version++, "23:30", "23:45")
                    : snapshot(version++, "22:00", "23:00"));
        }
        running.set(false);

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(reads.get()).isGreaterThan(1_000);
        assertThat(mixedStates.get())
                .as("readers observed a start/end combination from different change sets")
                .isZero();
    }

    @Test
    void olderVersionsAreNeverPublished() {
        store.publish(snapshot(10, "22:00", "23:00"));

        boolean accepted = store.publish(snapshot(9, "23:30", "23:45"));

        assertThat(accepted).isFalse();
        assertThat(store.version()).isEqualTo(10);
        assertThat(store.current().resolve(START_KEY, TENANT).value()).isEqualTo("22:00");
    }

    private ConfigSnapshot snapshot(long version, String start, String end) {
        List<ConfigDefinition> definitions = List.of(
                new ConfigDefinition(START_KEY, ConfigType.STRING, "00:00"),
                new ConfigDefinition(END_KEY, ConfigType.STRING, "00:01")
        );
        List<ConfigOverride> overrides = List.of(
                new ConfigOverride(START_KEY, ConfigLevel.CLIENT, "acme-corp", start),
                new ConfigOverride(END_KEY, ConfigLevel.CLIENT, "acme-corp", end)
        );
        return ConfigSnapshot.of(version, definitions, overrides);
    }
}
