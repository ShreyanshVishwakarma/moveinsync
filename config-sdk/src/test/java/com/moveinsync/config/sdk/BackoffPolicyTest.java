package com.moveinsync.config.sdk;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPolicyTest {

    private static final Duration BASE = Duration.ofMillis(500);
    private static final Duration MAX = Duration.ofSeconds(30);
    private static final Duration JITTER = Duration.ofMillis(500);

    @Test
    void growsExponentiallyAndCapsAtTheMaximum() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, Duration.ZERO, new Random(1));

        assertThat(policy.nextDelay(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.nextDelay(1)).isEqualTo(Duration.ofMillis(1_000));
        assertThat(policy.nextDelay(3)).isEqualTo(Duration.ofMillis(4_000));
        assertThat(policy.nextDelay(10)).isEqualTo(MAX);
        assertThat(policy.nextDelay(1_000)).isEqualTo(MAX);
    }

    @Test
    void jitterStaysWithinTheConfiguredWindow() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, JITTER, new Random(7));

        for (int attempt = 0; attempt < 12; attempt++) {
            long capped = Math.min(MAX.toMillis(), BASE.toMillis() * (1L << Math.min(attempt, 30)));
            Duration delay = policy.nextDelay(attempt);

            assertThat(delay.toMillis())
                    .isBetween(capped, capped + JITTER.toMillis());
        }
    }

    @Test
    void negativeAttemptsAreTreatedAsTheFirstAttempt() {
        BackoffPolicy policy = new BackoffPolicy(BASE, MAX, Duration.ZERO, new Random(3));

        assertThat(policy.nextDelay(-5)).isEqualTo(Duration.ofMillis(500));
    }
}
