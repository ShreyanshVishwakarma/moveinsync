package com.moveinsync.config.bench;

import com.moveinsync.config.sdk.ConfigSdk;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Long-running probe used by the kill-the-server test.
 *
 * <p>Prints one line per second with the value the service is serving and whether the live
 * stream is connected. The test kills the server mid-flight and expects the probe to keep
 * printing the same value from memory, then reconnect once the server returns.
 */
public final class KillServerProbe {

    public static void main(String[] args) throws Exception {
        String baseUrl = arg(args, "--url", "http://localhost:8080");
        String apiKey = arg(args, "--apiKey", "demo-employee-key");
        String clientId = arg(args, "--clientId", "acme-corp");
        String officeId = arg(args, "--officeId", "blr-office-1");
        String cacheFile = arg(args, "--cache", "config_cache.json");
        int seconds = intArg(args, "--seconds", 30);

        try (ConfigSdk sdk = ConfigSdk.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .clientId(clientId)
                .officeId(officeId)
                .cacheFile(Path.of(cacheFile))
                .pollInterval(Duration.ofSeconds(1))
                .build()) {

            sdk.start();

            long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
            while (System.nanoTime() < deadline) {
                String cutoff = sdk.find("booking_cutoff_minutes").orElse("unknown");
                System.out.println("PROBE t=" + Instant.now()
                        + " version=" + sdk.version()
                        + " cutoff=" + cutoff
                        + " connected=" + sdk.isConnected());
                System.out.flush();
                Thread.sleep(1000);
            }
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
