# MoveInSync — Hierarchical Tenant Configuration with Live Propagation

A configuration control plane for multi-tenant ride operations, plus the embedded client
library every service uses to read it. Settings are defined once (`GLOBAL`), overridden per
`CLIENT`, `OFFICE` or `SHIFT`, and the most specific value wins. Every write is an auditable,
atomic, versioned change set that reaches connected services within seconds.

```
moveinsync/
├── config-server/   Spring Boot control plane: REST, WebSocket, JPA, metrics
└── config-sdk/      Embedded client: microsecond reads, live deltas, disk fallback
```

**Status:** server and client SDK complete for the core case study. Authentication/tenant
isolation and the benchmark harness are the remaining milestones (see [Roadmap](#roadmap)).

---

## 1. Stack

| Concern | Choice | Why |
| --- | --- | --- |
| Language | **Java 21** | Explicitly preferred by the evaluator; OOPS and layering score best here |
| Server framework | **Spring Boot 3.3** (`web`, `websocket`, `data-jpa`, `validation`, `actuator`) | Only the pieces needed |
| Client SDK | **Plain Java 21 + Jackson** | JDK `HttpClient`/`WebSocket` mean the SDK drags no framework into consumer services |
| Local DB | **H2 in-memory** | Zero-setup demo and tests |
| Production DB | **PostgreSQL 16** | `docker compose` profile |
| Push channel | **Raw WebSocket** at `/ws/config` | Full control over frame shape, versioning and gap handling |
| Metrics | **Micrometer + Prometheus** | Counters, gauges, `/actuator/prometheus` |

The core algorithm is plain Java and tested without a web server or database: the server's
`ConfigSnapshot`/`ConfigValidator`, and the SDK's `ConfigState`/`StateApplier`.

---

## 2. Run it

Prerequisites: **JDK 21**. Maven is not required — the wrapper is included.

```bash
# control plane on http://localhost:8080 (H2 in memory, demo tenant seeded)
./mvnw -pl config-server -am spring-boot:run
```

Useful URLs:

- H2 console — `http://localhost:8080/h2-console` (JDBC URL `jdbc:h2:mem:configdb`, user `sa`, empty password)
- Health — `http://localhost:8080/actuator/health`
- Metrics — `http://localhost:8080/actuator/prometheus`
- WebSocket — `ws://localhost:8080/ws/config`

### With PostgreSQL

```bash
docker compose up --build     # postgres + config-server, one command
```

> Docker was not available on the machine this was built on, so the compose path is
> supplied but not yet verified. The H2 path is verified end to end.

### Tests

```bash
./mvnw test
```

Current result: **47 tests, 0 failures** across both modules (unit, integration,
concurrency, offline cold start and live propagation).

---

## 3. Using the SDK

Add the module to a consuming service (in this repo: `config-server` already depends on it
as a test artifact):

```xml
<dependency>
    <groupId>com.moveinsync</groupId>
    <artifactId>config-sdk</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
ConfigSdk sdk = ConfigSdk.builder()
        .baseUrl("http://config-server:8080")
        .clientId("acme-corp")              // tenant this service belongs to
        .officeId("blr-office-1")           // optional default context
        .cacheFile(Path.of("config_cache.json"))
        .pollInterval(Duration.ofSeconds(3)) // self-healing bound
        .eventListener(new ConfigEventListener() {
            @Override
            public void onUpdate(long version, int overrideCount) {
                log.info("configuration now at v{}", version);
            }
        })
        .build();

sdk.start();                                // loads cache, syncs, opens the stream

int cutoff = sdk.getInt("booking_cutoff_minutes");          // RAM read, no I/O
boolean escort = sdk.getBoolean("escort_required");
Duration window = sdk.getDuration("max_ride_minutes");
List<String> cabs = sdk.getList("allowed_cab_types");

// Per-request tenant context when a service serves several offices/shifts
String hyd = sdk.get("booking_cutoff_minutes", new TenantContext("acme-corp", "hyd-office-1", null));

sdk.close();                                // flushes the cache, closes the socket
```

An unknown key throws. `find(key)` returns `Optional` when presence itself is uncertain.

### What the SDK guarantees

| Guarantee | Mechanism |
| --- | --- |
| Reads are local and lock-free | Immutable `ConfigState` behind one `AtomicReference`; three hash lookups |
| No partially applied change set | Whole-state swap, never in-place mutation |
| Versions only move forward | `StateApplier` rejects duplicates and stale frames, detects gaps |
| Missed pushes heal | Version poll (default 3 s) + gap-triggered snapshot request |
| Server down at cold start | `config_cache.json` loaded before the first read |
| Reconnect storms are damped | Jittered exponential backoff, capped at 30 s |
| Crashing mid-write cannot corrupt the cache | Temp file + atomic move, coalescing single writer |

### SDK configuration

| Builder option | Default | Purpose |
| --- | --- | --- |
| `baseUrl` | required | Control plane URL (`http`/`https`, converted to `ws`/`wss`) |
| `clientId` / `officeId` / `shiftId` | null | Default tenant context |
| `cacheFile` | `config_cache.json` | Disk fallback; `null` disables it |
| `pollInterval` | 3 s | Upper bound on staleness when a push is lost |
| `connectTimeout` / `requestTimeout` | 5 s | Transport timeouts |
| `backoff(base, max, jitter)` | 500 ms / 30 s / 500 ms | Reconnect policy |
| `eventListener` | no-op | Hooks for updates, gaps, sync failures and connection changes |

`stats()` exposes version, applied/ignored counts, gaps, resyncs, failures, reconnects and
the last sync time. Details in [`docs/sdk.md`](docs/sdk.md).

---

## 4. API

All control-plane endpoints are under `/api/v1`. Values are strings on the wire; the
definition's `type` decides how they are validated and interpreted.

### Definitions

```bash
curl -X POST http://localhost:8080/api/v1/definitions \
  -H 'Content-Type: application/json' \
  -d '{
        "key": "booking_cutoff_minutes",
        "type": "DURATION",
        "defaultValue": "60",
        "minValue": 0,
        "maxValue": 1440,
        "description": "Minutes before departure that bookings close"
      }'

curl http://localhost:8080/api/v1/definitions
curl http://localhost:8080/api/v1/definitions/booking_cutoff_minutes
```

### Read a resolved value (with provenance)

```bash
curl 'http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&clientId=acme-corp&officeId=blr-office-1'
# {"key":"booking_cutoff_minutes","value":"30","sourceLevel":"OFFICE","sourceEntityId":"blr-office-1","version":1}
```

### Explain precedence

```bash
curl 'http://localhost:8080/api/v1/config/explain?key=booking_cutoff_minutes&clientId=acme-corp&officeId=blr-office-1&shiftId=night'
```

Returns every level in precedence order (`SHIFT → OFFICE → CLIENT → GLOBAL`), the value at
each level, and which one won.

### Publish an atomic change set

```bash
curl -X POST http://localhost:8080/api/v1/change-sets \
  -H 'Content-Type: application/json' \
  -d '{
        "author": "operator-1",
        "reason": "Tighten night window and cut-off for Bengaluru",
        "changes": [
          {"key": "night_safety_window", "level": "OFFICE", "entityId": "blr-office-1", "value": "21:00-23:30"},
          {"key": "booking_cutoff_minutes", "level": "OFFICE", "entityId": "blr-office-1", "value": "25"}
        ]
      }'
```

Both entries become visible under **one** version, or neither does. A blank value deletes
the override and falls back to the next level.

### Sync clients

```bash
curl http://localhost:8080/api/v1/config/version
curl 'http://localhost:8080/api/v1/config/sync?since=1'   # delta (or a full snapshot if too far behind)
curl http://localhost:8080/api/v1/config/snapshot          # full immutable state
```

### History and rollback

```bash
curl http://localhost:8080/api/v1/change-sets
curl http://localhost:8080/api/v1/change-sets/2

# Look before you leap
curl -X POST http://localhost:8080/api/v1/rollbacks \
  -H 'Content-Type: application/json' \
  -d '{"targetVersion": 1, "author": "operator-1", "reason": "Bad window", "dryRun": true}'

# Publish the rollback as a new version
curl -X POST http://localhost:8080/api/v1/rollbacks \
  -H 'Content-Type: application/json' \
  -d '{"targetVersion": 1, "author": "operator-1", "reason": "Bad window", "dryRun": false}'
```

### Validation errors

```json
{
  "timestamp": "2026-10-30T17:56:12.538Z",
  "status": 422,
  "code": "VALIDATION_FAILED",
  "message": "Invalid configuration value(s)",
  "details": ["booking_cutoff_minutes: duration must be >= 0 minutes"],
  "path": "/api/v1/change-sets"
}
```

No stack traces, no payloads, no tenant PII in error responses or logs.

### WebSocket protocol

| Direction | Frame |
| --- | --- |
| server → client | `{"type":"hello","version":42}` on connect |
| client → server | `{"type":"subscribe","version":41}` |
| server → client | `{"type":"delta","version":43,"entries":[...]}` |
| server → client | `{"type":"snapshot","version":43,"snapshot":{...}}` when the client is behind |

---

## 5. Consistency model

Configuration is read from an immutable in-memory snapshot swapped with a single
`AtomicReference.set`. Each published change set is assigned a strictly increasing version
by the database (the change-set primary key), is persisted in one transaction, and only
after that commit is a new snapshot built and swapped in. Reads therefore never block and
never observe a partial change set — they see version *N* completely or *N+1* completely.

Propagation is best-effort push (sub-second in practice) with a hard bound of one poll
interval: a client that misses a frame detects a version gap from the next frame's version
and fetches a snapshot immediately, and a periodic version check heals a client whose socket
died silently. If the server is unreachable, clients keep serving the last snapshot and can
cold-start from disk, so they can never serve data older than their last successful sync.

Versions only move forward. A delta at `current + 1` is applied, anything `<= current` is
ignored as duplicate/stale, and anything `> current + 1` is treated as a gap: the state is
left untouched and a full snapshot is requested.

---

## 6. Data model

```
config_definitions   (id, config_key, type, global_default_value, validation_regex,
                      allowed_values, min_value, max_value, description, updated_at)

config_overrides     (id, config_key, level, entity_id, config_value, updated_by, updated_at)
                     UNIQUE (config_key, level, entity_id)

change_sets          (id /* = version, monotonic */, author, reason, created_at,
                      reverts_to_version)

change_set_entries   (id, change_set_id, config_key, level, entity_id,
                      old_value, new_value, action)
```

`change_sets.id` is generated by the database identity/sequence, which gives the
monotonicity guarantee without a separate counter that could drift.

---

## 7. Complexity and memory

Let *D* = definitions, *O* = overrides, *L* = audit entries, *H* = hierarchy depth (fixed at 4).

| Operation | Time | Space |
| --- | --- | --- |
| SDK/server resolve one key | **O(H)** = 3 hash lookups + 1 default lookup | O(1) |
| Explain one key | O(H) | O(H) |
| Snapshot build (after commit) | O(D + O) | O(D + O) |
| SDK delta apply of *k* entries | O(k + tenants touched per level) | O(k) |
| Commit a change set of size *k* | O(k) lookups + O(k) writes | O(k) |
| Delta sync since *v* | O(L_v) | O(L_v) |
| Rollback to *v* | O(L_v) replay + O(O) diff | O(O) |
| Storage | — | O(D + O + L) |

The server rebuilds the whole snapshot after a commit. That is deliberate: at tenant-config
scale the rebuild is sub-millisecond and it removes an entire class of incremental-update
bugs. Where it stops scaling: the snapshot must fit in one JVM heap, and the single-writer
gate caps publication throughput (roughly a few thousand change sets/second). Beyond that,
the next step is per-key incremental updates plus a partitioned version allocator. On the
client, the state is O(D + O); a service with thousands of tenants per instance should hold
only its own tenant's overrides (the snapshot endpoint is not yet tenant-filtered — see
Roadmap).

---

## 8. Observability

| Server metric | Type | What to alarm on |
| --- | --- | --- |
| `config_commits_total` | counter | Drop to zero during business hours |
| `config_rejections_total` | counter | Spike = bad automation or operator error |
| `config_rollbacks_total` | counter | Any increase deserves a look |
| `config_resync_requests_total` | counter | Spike = clients missing pushes |
| `config_ws_connected_clients` | gauge | Sudden drop = reconnect storm |
| `config_ws_broadcasts_total` | counter | Compare with commits |
| `config_ws_failures_total` | counter | Socket write failures |
| `config_ws_pending_broadcasts` | gauge | Queue depth near capacity |

The SDK exposes `stats()` (version, deltas applied, stale ignored, gaps, resyncs, sync
failures, reconnects, connected, last sync) and `ConfigEventListener` callbacks, so a host
service can publish them to its own Micrometer registry without the SDK depending on it.

Structured log lines are emitted for every publication (`version`, `author`, entry count),
every rejection (`code`, `path`), and every SDK sync/gap — never the payload.

---

## 9. Tests: what each suite proves

| Suite | Tests | Proves |
| --- | --- | --- |
| `ConfigSnapshotResolutionTest` | 6 | Hierarchy precedence and explain trace (server) |
| `ConfigValidatorTest` | 9 | Type rules: negative durations, inverted windows, enums, lists, ranges, blanks |
| `SnapshotAtomicityTest` | 2 | Readers never mix change sets; older versions are not published |
| `ConfigChangeFlowIntegrationTest` | 5 | Commit/version/reject/sync/rollback against real persistence |
| `ConfigStateTest` | 9 | SDK resolution, delta update/delete, JSON round trip, tolerant parsing |
| `StateApplierTest` | 7 | Exact version rules: apply, duplicate, stale, gap, snapshot replace |
| `BackoffPolicyTest` | 3 | Exponential growth, cap, jitter bounds |
| `SnapshotDiskCacheTest` | 3 | Persistence, missing file, corrupt file |
| `ConfigSdkOfflineTest` | 1 | Cold start from disk while the server is unreachable |
| `SdkAtomicityTest` | 1 | 4 readers vs 2 000 delta swaps, zero mixed states |
| `ConfigSdkPropagationIntegrationTest` | 1 | Real server + real SDK, push-only delivery in < 5 s |

The propagation test sets the poll interval to 30 seconds and still observes the change in
under five, so polling cannot be what delivered it.

---

## 10. Trade-offs and deliberate omissions

- **Single writer.** Publication is serialised with a fair lock to keep version order and
  delta coherence. Readers never take the lock. Right trade for a control plane; multi-node
  writers would need a shared allocator and an outbox.
- **Full snapshot rebuild per commit.** O(D + O) per write; simple and correct. Revisit only
  if the config set grows past a few hundred thousand rows.
- **SDK keeps the whole tenant map.** Simple, and the read path stays O(1). A truly
  multi-tenant *service* should receive a filtered snapshot; that is a server-side change,
  listed in the roadmap.
- **Rollback is forward-only.** It publishes a new change set so clients already ahead
  converge normally.
- **Definition changes do not consume a version.** They republish the snapshot over
  WebSocket at the current version. Production would treat definitions as migrations.
- **Same-day time windows only.** `HH:mm-HH:mm` requires start < end, matching the case
  study's rejection rule. Overnight windows need an explicit flag or a richer type.
- **Not implemented yet:** authentication/RBAC, server-side tenant filtering, the 200-client
  propagation benchmark, and staged rollout.

---

## 11. Roadmap

1. **Auth & tenancy** — API keys per service, roles (admin / operator / employee), and
   server-side enforcement that a caller only sees its own `clientId` subtree.
2. **Staged rollout** — a change set scoped to two offices, then promoted to everyone.
3. **Benchmarks** — JMH for local reads (target < 1 µs), 200 simulated SDK clients for
   propagation p50/p99, and a kill-the-server recovery test.
4. **Failure injection** — duplicate, out-of-order and dropped frames between server and
   SDK (the pure `StateApplier` tests cover the rules; the harness would cover delivery).
5. **Delta retention** — serve `/config/sync` from a bounded in-memory ring instead of
   replaying audit rows.

---

## 12. Project layout

```
config-server/src/main/java/com/moveinsync/config/
├── controller/     REST edge (definitions, query, change sets, rollback)
├── service/        resolution orchestration, commit, sync, rollback
│   └── validation/ one strategy per ConfigType
├── repository/     Spring Data JPA interfaces
├── domain/         entities and immutable value objects
├── snapshot/       immutable ConfigSnapshot + atomic SnapshotStore
├── ws/             WebSocket handler, publisher, configuration
├── dto/            request/response records
├── exception/      ApiException, ValidationException, global handler
├── metrics/        Micrometer counters and gauges
└── config/         demo data seeder

config-sdk/src/main/java/com/moveinsync/config/sdk/
├── ConfigSdk.java          public client and builder
├── ConfigState.java        immutable snapshot + resolution
├── StateApplier.java       version rules and atomic state transitions
├── ConfigWebSocketTransport.java   push channel with jittered reconnect
├── ConfigHttpTransport.java        version check + snapshot fetch
├── SnapshotDiskCache.java          atomic, coalescing disk fallback
├── BackoffPolicy.java              exponential backoff with jitter
└── Wire.java / WireCodec.java      JSON contract
```

See [`docs/design.md`](docs/design.md) for the architecture diagram, the atomic-commit
sequence diagram and the failure-mode table, and [`docs/sdk.md`](docs/sdk.md) for the SDK
lifecycle and protocol details.
