package com.moveinsync.config.sdk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readers hammer the SDK while deltas flip a two-key change set. Because every read
 * takes one state reference, a new start can never be paired with an old end.
 */
class SdkAtomicityTest {

    private static final String START = "night_window_start";
    private static final String END = "night_window_end";
    private static final TenantContext TENANT = new TenantContext("acme-corp", null, null);

    private final StateApplier applier = new StateApplier();
    private final ExecutorService pool = Executors.newFixedThreadPool(5);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void readersNeverSeeANewStartWithAnOldEnd() throws Exception {
        applier.replaceSnapshot(pair(1, "22:00", "23:00"));

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong reads = new AtomicLong();
        AtomicLong mixed = new AtomicLong();
        CountDownLatch gate = new CountDownLatch(1);

        for (int i = 0; i < 4; i++) {
            pool.submit(() -> {
                gate.await();
                while (running.get()) {
                    ConfigState view = applier.current();
                    String start = view.resolve(START, TENANT).value();
                    String end = view.resolve(END, TENANT).value();

                    boolean pairA = "22:00".equals(start) && "23:00".equals(end);
                    boolean pairB = "23:30".equals(start) && "23:45".equals(end);
                    if (!pairA && !pairB) {
                        mixed.incrementAndGet();
                    }
                    reads.incrementAndGet();
                }
                return null;
            });
        }

        gate.countDown();

        for (int version = 2; version < 2_002; version++) {
            boolean first = version % 2 == 0;
            StateApplier.Result result = applier.applyDelta(new Wire.Delta(version, List.of(
                    new Wire.Entry(START, "CLIENT", "acme-corp", null, first ? "23:30" : "22:00"),
                    new Wire.Entry(END, "CLIENT", "acme-corp", null, first ? "23:45" : "23:00")
            )));
            assertThat(result.outcome()).isEqualTo(StateApplier.Outcome.APPLIED);
        }
        running.set(false);

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(reads.get()).isGreaterThan(1_000);
        assertThat(mixed.get()).as("mixed start/end observed").isZero();
    }

    private Wire.Snapshot pair(long version, String start, String end) {
        return new Wire.Snapshot(
                version,
                List.of(
                        new Wire.Definition(START, "TIME_WINDOW", "00:00", null, null, null, null),
                        new Wire.Definition(END, "TIME_WINDOW", "00:01", null, null, null, null)
                ),
                Map.of("CLIENT", Map.of("acme-corp", Map.of(START, start, END, end)))
        );
    }
}
