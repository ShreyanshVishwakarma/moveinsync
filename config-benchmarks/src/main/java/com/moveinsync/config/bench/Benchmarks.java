package com.moveinsync.config.bench;

import java.util.Arrays;

/**
 * Entry point for the fat jar.
 *
 * <pre>
 *   java -jar config-benchmarks-...-jar-with-dependencies.jar read
 *   java -jar config-benchmarks-...-jar-with-dependencies.jar propagate --clients 200 --url http://localhost:8080
 * </pre>
 */
public final class Benchmarks {

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "read" : args[0];
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);

        switch (mode) {
            case "read" -> ReadPathBenchmark.main(rest);
            case "propagate" -> PropagationBenchmark.main(rest);
            case "probe" -> KillServerProbe.main(rest);
            default -> {
                System.err.println("Unknown mode '" + mode + "'. Use: read | propagate | probe");
                System.exit(64);
            }
        }
    }
}
