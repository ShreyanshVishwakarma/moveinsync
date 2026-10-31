package com.moveinsync.config.sdk;

import com.moveinsync.config.domain.ConfigLevel;
import com.moveinsync.config.dto.CommitChangeSetRequest;
import com.moveinsync.config.dto.OverrideChange;
import com.moveinsync.config.service.ConfigChangeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the read path contract: a real server, a real SDK, and a change
 * that must reach the client through the push channel in under five seconds.
 *
 * <p>The poll interval is deliberately 30 seconds, so polling cannot be what delivers the
 * update: only the WebSocket delta can.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "moveinsync.seed.enabled=true"
)
@ActiveProfiles("test")
class ConfigSdkPropagationIntegrationTest {

    private static final String KEY = "booking_cutoff_minutes";
    private static final String CLIENT = "acme-corp";
    private static final String OFFICE = "blr-office-1";

    @LocalServerPort
    private int port;

    @Autowired
    private ConfigChangeService changeService;

    @TempDir
    Path tempDir;

    @Test
    void aPublishedChangeReachesTheSdkInUnderFiveSeconds() throws Exception {
        try (ConfigSdk sdk = ConfigSdk.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .apiKey(com.moveinsync.config.config.DemoDataSeeder.EMPLOYEE_KEY)
                .clientId(CLIENT)
                .officeId(OFFICE)
                .cacheFile(tempDir.resolve("sdk-cache.json"))
                .pollInterval(Duration.ofSeconds(30))
                .build()) {

            sdk.start();

            await("initial snapshot", Duration.ofSeconds(15),
                    () -> sdk.version() >= 1 && "30".equals(sdk.get(KEY)));

            // Hierarchy is resolved client-side from the bootstrapped snapshot.
            assertThat(sdk.resolve(KEY, new TenantContext(CLIENT, "hyd-office-1", null)).value())
                    .isEqualTo("45");
            assertThat(sdk.explain(KEY, new TenantContext(CLIENT, OFFICE, null)))
                    .filteredOn(ResolutionStep::applied)
                    .singleElement()
                    .extracting(ResolutionStep::level)
                    .isEqualTo(com.moveinsync.config.sdk.ConfigLevel.OFFICE);

            long startedAt = System.nanoTime();
            changeService.commit(new CommitChangeSetRequest(
                    "sdk-integration-test",
                    "Cut-off change that must be pushed, not polled",
                    List.of(new OverrideChange(KEY, ConfigLevel.OFFICE, OFFICE, "25", CLIENT))
            ));

            await("pushed delta", Duration.ofSeconds(5), () -> "25".equals(sdk.get(KEY)));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertThat(elapsedMillis).isLessThan(5_000);
            assertThat(sdk.version()).isGreaterThanOrEqualTo(2);
            assertThat(sdk.isConnected()).isTrue();
            assertThat(sdk.stats().deltasApplied()).isGreaterThanOrEqualTo(1);
        }
    }

    private void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for: " + what);
    }
}
