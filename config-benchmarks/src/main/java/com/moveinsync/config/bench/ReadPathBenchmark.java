package com.moveinsync.config.bench;

import com.moveinsync.config.sdk.ConfigState;
import com.moveinsync.config.sdk.TenantContext;
import com.moveinsync.config.sdk.Wire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Measures the embedded read path: {@code ConfigState.resolve} against a realistic
 * snapshot. No network, no locks, no Spring - exactly what a service does per request.
 *
 * <p>Run:
 * <pre>
 *   java -jar config-benchmarks/target/config-benchmarks-0.1.0-SNAPSHOT-jar-with-dependencies.jar
 * </pre>
 */
public final class ReadPathBenchmark {

    private static final int DEFINITIONS = 200;
    private static final int CLIENTS = 500;
    private static final int OFFICES_PER_CLIENT = 10;
    private static final String HOT_KEY = "setting_17";

    public static void main(String[] args) {
        ConfigState state = buildState();
        TenantContext context = new TenantContext("client-42", "client-42-office-3", null);

        System.out.println("=== Local read path ===");
        System.out.println("machine        : " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", Java " + System.getProperty("java.version"));
        System.out.println("state size     : " + state.definitions().size() + " definitions, "
                + state.overrideCount() + " overrides");

        // Warm up the JIT on the exact call shape we are measuring.
        long sink = 0;
        for (int i = 0; i < 3_000_000; i++) {
            sink += state.resolve(HOT_KEY, context).value().length();
        }

        int throughputOps = 10_000_000;
        long started = System.nanoTime();
        for (int i = 0; i < throughputOps; i++) {
            sink += state.resolve(HOT_KEY, context).value().length();
        }
        long elapsed = System.nanoTime() - started;

        int sampleOps = 500_000;
        long[] samples = new long[sampleOps];
        for (int i = 0; i < sampleOps; i++) {
            long begin = System.nanoTime();
            sink += state.resolve(HOT_KEY, context).value().length();
            samples[i] = System.nanoTime() - begin;
        }

        long[] sorted = BenchStats.sortedCopy(samples);
        double opsPerSecond = throughputOps / (elapsed / 1_000_000_000.0);

        System.out.printf("throughput     : %,.0f resolves/sec (%d ops in %.2f s)%n",
                opsPerSecond, throughputOps, elapsed / 1_000_000_000.0);
        System.out.println("latency (n=" + sampleOps + ", includes nanoTime overhead)");
        System.out.println("  p50          : " + BenchStats.nanos(BenchStats.percentile(sorted, 50)));
        System.out.println("  p99          : " + BenchStats.nanos(BenchStats.percentile(sorted, 99)));
        System.out.println("  p99.9        : " + BenchStats.nanos(BenchStats.percentile(sorted, 99.9)));
        System.out.println("  max          : " + BenchStats.nanos(sorted[sorted.length - 1]));
        System.out.println("sink           : " + sink);
    }

    private static ConfigState buildState() {
        List<Wire.Definition> definitions = new ArrayList<>(DEFINITIONS);
        for (int i = 0; i < DEFINITIONS; i++) {
            definitions.add(new Wire.Definition(
                    "setting_" + i,
                    i % 4 == 0 ? "DURATION" : "STRING",
                    "60",
                    null,
                    null,
                    0.0,
                    1440.0
            ));
        }

        Map<String, Map<String, Map<String, String>>> overrides = new HashMap<>();
        Map<String, Map<String, String>> clients = new HashMap<>();
        Map<String, Map<String, String>> offices = new HashMap<>();

        for (int client = 0; client < CLIENTS; client++) {
            String clientId = "client-" + client;
            Map<String, String> clientValues = new HashMap<>();
            for (int i = 0; i < DEFINITIONS; i += 2) {
                clientValues.put("setting_" + i, String.valueOf(client + i));
            }
            clients.put(clientId, clientValues);

            for (int office = 0; office < OFFICES_PER_CLIENT; office++) {
                String officeId = clientId + "-office-" + office;
                Map<String, String> officeValues = new HashMap<>();
                for (int i = 0; i < DEFINITIONS; i += 5) {
                    officeValues.put("setting_" + i, String.valueOf(office + i));
                }
                offices.put(officeId, officeValues);
            }
        }

        overrides.put("CLIENT", clients);
        overrides.put("OFFICE", offices);
        return ConfigState.fromSnapshot(new Wire.Snapshot(1, definitions, overrides));
    }
}
