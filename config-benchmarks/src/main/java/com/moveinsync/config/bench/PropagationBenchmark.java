package com.moveinsync.config.bench;

import com.moveinsync.config.sdk.ConfigSdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Measures end-to-end propagation: N embedded SDKs hold a live WebSocket, one change is
 * published, and we record how long each client takes to observe it.
 *
 * <p>The SDKs are configured with a 60 second poll interval, so only the push channel can
 * deliver the update in the measured window.
 *
 * <p>Run against a live server:
 * <pre>
 *   java -jar config-benchmarks/target/config-benchmarks-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
 *        --url http://localhost:8080 --clients 200
 * </pre>
 */
public final class PropagationBenchmark {

    private static final String KEY = "booking_cutoff_minutes";

    public static void main(String[] args) throws Exception {
        String baseUrl = arg(args, "--url", "http://localhost:8080");
        String employeeKey = arg(args, "--employeeKey", "demo-employee-key");
        String operatorKey = arg(args, "--operatorKey", "demo-operator-key");
        String clientId = arg(args, "--clientId", "acme-corp");
        String officeId = arg(args, "--officeId", "blr-office-1");
        int clients = intArg(args, "--clients", 200);

        System.out.println("=== Live propagation ===");
        System.out.println("machine        : " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch") + ", Java " + System.getProperty("java.version"));
        System.out.println("server         : " + baseUrl);
        System.out.println("clients        : " + clients);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<ConfigSdk> sdks = new ArrayList<>(clients);

        try {
            for (int i = 0; i < clients; i++) {
                ConfigSdk sdk = ConfigSdk.builder()
                        .baseUrl(baseUrl)
                        .apiKey(employeeKey)
                        .clientId(clientId)
                        .officeId(officeId)
                        .httpClient(http)
                        .cacheFile(null)
                        // Push only: polling cannot mask a broken broadcast.
                        .pollInterval(Duration.ofSeconds(60))
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                sdk.start();
                sdks.add(sdk);
            }

            long bootstrapDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < bootstrapDeadline) {
                long ready = sdks.stream().filter(sdk -> sdk.find(KEY).isPresent()).count();
                if (ready == clients) {
                    break;
                }
                Thread.sleep(50);
            }
            long ready = sdks.stream().filter(sdk -> sdk.find(KEY).isPresent()).count();
            System.out.println("bootstrapped   : " + ready + "/" + clients + " clients");
            if (ready < clients) {
                throw new IllegalStateException("Only " + ready + " clients bootstrapped");
            }

            String baseline = sdks.get(0).get(KEY);
            String target = "31".equals(baseline) ? "32" : "31";

            long commitStart = System.nanoTime();
            commit(http, baseUrl, operatorKey, clientId, target);
            long commitMillis = (System.nanoTime() - commitStart) / 1_000_000;
            System.out.println("committed      : " + baseline + " -> " + target
                    + " (HTTP round trip " + commitMillis + " ms)");

            long[] latencies = new long[clients];
            Arrays.fill(latencies, -1);
            long deadline = commitStart + Duration.ofSeconds(20).toNanos();
            int converged = 0;

            while (converged < clients && System.nanoTime() < deadline) {
                for (int i = 0; i < clients; i++) {
                    if (latencies[i] < 0 && target.equals(sdks.get(i).get(KEY))) {
                        latencies[i] = (System.nanoTime() - commitStart) / 1_000;
                        converged++;
                    }
                }
                Thread.sleep(2);
            }

            long[] observed = Arrays.stream(latencies).filter(value -> value >= 0).sorted().toArray();
            System.out.println("converged      : " + converged + "/" + clients);
            if (observed.length > 0) {
                System.out.println("latency p50    : " + BenchStats.nanos(BenchStats.percentile(observed, 50) * 1_000));
                System.out.println("latency p99    : " + BenchStats.nanos(BenchStats.percentile(observed, 99) * 1_000));
                System.out.println("latency max    : " + BenchStats.nanos(observed[observed.length - 1] * 1_000));
                long withinFiveSeconds = Arrays.stream(observed).filter(value -> value <= 5_000_000).count();
                System.out.printf("within 5s      : %d/%d (%.1f%%)%n",
                        withinFiveSeconds, clients, 100.0 * withinFiveSeconds / clients);
            }
            if (converged < clients) {
                System.exit(2);
            }
        } finally {
            for (ConfigSdk sdk : sdks) {
                sdk.close();
            }
        }
    }

    private static void commit(HttpClient http,
                               String baseUrl,
                               String operatorKey,
                               String clientId,
                               String value) throws Exception {
        String body = """
                {"author":"benchmark","reason":"propagation benchmark",
                 "changes":[{"key":"%s","level":"OFFICE","entityId":"%s","value":"%s","clientId":"%s"}]}
                """.formatted(KEY, "blr-office-1", value, clientId);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/change-sets"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-API-Key", operatorKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Commit failed with status " + response.statusCode()
                    + ": " + response.body());
        }
    }

    private static String arg(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static int intArg(String[] args, String name, int fallback) {
        return Integer.parseInt(arg(args, name, String.valueOf(fallback)));
    }
}
