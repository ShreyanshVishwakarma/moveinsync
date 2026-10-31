# Benchmarks and Failure Test

All numbers were produced on the machine below, against this repository, with the commands
shown. They are intended to be reproducible, not to be a universal claim.

**Machine:** Windows 11, Intel Core i5-12500H (12 cores / 16 threads), 15.6 GB RAM,
Temurin JDK 21.0.13. Loopback networking, no TLS, everything on one host.

---

## 1. Local read path

`config-benchmarks` builds a snapshot with **200 definitions and 250 000 overrides**
(500 clients × 10 offices + client-level values) and resolves one hot key with an
office-scoped context.

```bash
./mvnw -q -pl config-benchmarks -am -DskipTests package
java -jar config-benchmarks/target/config-benchmarks-0.1.0-SNAPSHOT-jar-with-dependencies.jar read
```

```
=== Local read path ===
machine        : Windows 11 amd64, Java 21.0.13
state size     : 200 definitions, 250000 overrides
throughput     : 19,311,506 resolves/sec (10000000 ops in 0.52 s)
latency (n=500000, includes nanoTime overhead)
  p50          : 100 ns
  p99          : 200 ns
  p99.9        : 1.20 us
  max          : 46.70 us
```

A second run produced 19 013 981 resolves/sec with p99.9 at 400 ns. The per-operation
measurement adds roughly 20–30 ns of `System.nanoTime()` overhead, so the reported latency is
an upper bound. Throughput is measured without per-op timing (one loop around 10 M resolves).

**Why it is fast:** three hash lookups (`SHIFT`, `OFFICE`, `CLIENT`), one definition lookup
for the global default, and no locks, I/O or allocation beyond the small result record.

---

## 2. Live propagation to 200 clients

200 embedded SDKs connect to a real server, each with its WebSocket open and a **60-second**
poll interval. Polling is therefore not what delivers the update; only the push channel can.

```bash
# terminal 1
./mvnw -pl config-server -am spring-boot:run

# terminal 2
java -jar config-benchmarks/target/config-benchmarks-0.1.0-SNAPSHOT-jar-with-dependencies.jar \
     propagate --clients 200
```

```
=== Live propagation ===
machine        : Windows 11 amd64, Java 21.0.13
server         : http://localhost:8080
clients        : 200
bootstrapped   : 200/200 clients
committed      : 30 -> 31 (HTTP round trip 155 ms)
converged      : 200/200
latency p50    : 271.35 ms
latency p99    : 273.67 ms
latency max    : 273.68 ms
within 5s      : 200/200 (100.0%)
```

The near-identical percentiles reflect a single ordered broadcast thread writing 200 sockets
back to back: the spread between the first and last client is a couple of milliseconds. The
target from the case study is 5 seconds; the observed result is ~0.27 s with 200 clients.

---

## 3. Kill-the-server test

Script: [`scripts/kill-server-test.ps1`](../scripts/kill-server-test.ps1).

What it does:

1. Starts the real server on a **file-backed H2 database** and waits for the demo seed.
2. Starts an SDK probe that prints `version`, the resolved value and `connected` every second.
3. Publishes `booking_cutoff_minutes` 30 → 29 and waits for the probe to see it.
4. **Kills the server JVM** (`Stop-Process -Force`).
5. Asserts the probe still prints `cutoff=29` with `connected=false` — reads keep working
   from memory, and the SDK observes the outage.
6. Restarts the server against the same database.
7. Asserts the probe reconnects, still at `version=2`, still serving `cutoff=29` — the change
   survived the crash.

```
1) starting server on http://127.0.0.1:8091 (file-backed H2)
   waiting for demo data
2) starting SDK probe
   probe connected
3) publishing booking_cutoff_minutes 30 -> 29
   change observed by the probe
4) killing the server JVM
   during outage: PROBE t=... version=2 cutoff=29 connected=false
5) restarting the server against the same database
   after restart: PROBE t=... version=2 cutoff=29 connected=true

PASS: reads survived the outage; the published change survived the restart.
```

The test takes roughly 30 seconds including two Spring Boot starts.

---

## 4. What these numbers do and do not show

- They show that the read path is far below the microsecond target and that fan-out to a few
  hundred clients fits comfortably inside the five-second bound on a single node.
- They do **not** show behaviour under multi-node deployment, TLS, or thousands of clients.
  The single broadcast thread and in-process snapshot are deliberate single-node choices; the
  scaling limits are discussed in the README.
- The propagation harness runs all 200 clients in one JVM. That makes the measurement
  conservative on the SDK side (shared CPU) and optimistic on the network side (loopback).
