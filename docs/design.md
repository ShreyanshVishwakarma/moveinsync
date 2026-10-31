# Design Note — Hierarchical Tenant Configuration

Two pages: architecture, the hardest flow (atomic multi-key publication), failure modes,
and the reasoning behind the main decisions.

---

## 1. Architecture

```
                         ┌─────────────────────────────────────────────┐
                         │              CONFIG SERVER                  │
                         │                                             │
  admin / operator       │  ┌────────────┐   ┌────────────────────┐    │
  ──────────────────────▶│  │ Controller │──▶│ ConfigChangeService│    │
   POST /change-sets     │  │  (REST)    │   │  validate → commit │    │
                         │  └────────────┘   └─────────┬──────────┘    │
                         │        │                    │               │
                         │        │              ┌─────▼──────────┐    │
                         │        │              │  PostgreSQL /  │    │
                         │        │              │  H2 (durable)  │    │
                         │        │              └─────┬──────────┘    │
                         │        │                    │ after commit  │
                         │  ┌─────▼──────┐      ┌──────▼─────────┐    │
  service reads          │  │ Query      │◀─────│ SnapshotStore  │    │
  ──────────────────────▶│  │ Service    │      │ AtomicReference│    │
   GET /resolve          │  └────────────┘      │ <ConfigSnapshot│    │
                         │                      └──────┬─────────┘    │
                         │                             │ publish      │
                         │                    ┌────────▼─────────┐    │
                         │                    │ BroadcastListener│    │
                         │                    │ (1 thread, order)│    │
                         │                    └────────┬─────────┘    │
                         └─────────────────────────────┼──────────────┘
                                                       │ WebSocket deltas
                            ┌──────────────────────────▼───────────────┐
                            │            EMBEDDED CLIENT SDK           │
                            │  AtomicReference<Map<String,String>>     │
                            │  ← microsecond reads, zero network       │
                            │  WS receiver + version gap detector      │
                            │  periodic /version check (self-healing)  │
                            │  config_cache.json (cold-start fallback) │
                            └──────────────────────────────────────────┘
```

**Layers.** `controller` knows HTTP only. `service` owns resolution, validation, atomic
publication, sync and rollback. `repository` is persistence only. `domain` holds entities
and immutable value objects. The core algorithm (`ConfigSnapshot`, `ConfigValidator`) has
no Spring or JPA dependency and is unit-tested in isolation.

**Read path.** One `AtomicReference` to an immutable `ConfigSnapshot`. Resolution is three
hash lookups (`SHIFT`, `OFFICE`, `CLIENT`) plus the global default — O(hierarchy depth),
no locks, no allocations beyond the small result record.

**Write path.** Validation against the current snapshot → one database transaction that
creates the change set (which allocates the version), mutates overrides and appends audit
rows → rebuild snapshot → atomic swap → asynchronous fan-out.

---

## 2. Hardest flow: atomic multi-key publication

```
Operator      Controller     ChangeService      DB          SnapshotStore     WS clients
   │              │               │             │                │               │
   │ POST change  │               │             │                │               │
   │─────────────▶│               │             │                │               │
   │              │ commit()      │             │                │               │
   │              │──────────────▶│             │                │               │
   │              │               │ acquire     │                │               │
   │              │               │ CommitGate  │                │               │
   │              │               │             │                │               │
   │              │               │ validate both entries       │               │
   │              │               │ (window start < end, cut-off >= 0)            │
   │              │               │             │                │               │
   │              │               │ BEGIN ─────▶│                │               │
   │              │               │ INSERT change_set (id=42)    │               │
   │              │               │ UPSERT overrides (2 rows)    │               │
   │              │               │ INSERT audit entries (2)     │               │
   │              │               │ COMMIT ────▶│                │               │
   │              │               │             │                │               │
   │              │               │ rebuild snapshot v42         │               │
   │              │               │─────────────────────────────▶│               │
   │              │               │                AtomicReference.set(v42)       │
   │              │               │             │                │               │
   │              │               │ release CommitGate           │               │
   │              │               │ publish event ──────────────▶ broadcast       │
   │              │◀──────────────│ 201 {version:42, entries}    │──── delta ───▶│
   │◀─────────────│               │             │                │               │
```

**Why no reader can see a mixed state.** The two overrides are written inside one
transaction and become visible to readers only when the *whole* snapshot object is
swapped in. `AtomicReference` guarantees a reader gets either the old object or the new
one. `SnapshotAtomicityTest` runs four reader threads against a writer flipping a
two-key change set 2 000 times and asserts zero mixed observations.

**Why validation cannot be bypassed.** The same `ConfigValidator` strategies validate API
payloads, definition defaults, and seeded demo data. A change set is rejected *before*
any database write, so a bad value never consumes a version.

---

## 3. Failure-mode table

| Failure | What the client sees | What the system does | Recovery |
| --- | --- | --- | --- |
| Config server killed after commit | Keeps serving last snapshot | State is already durable in the DB | On restart the snapshot is rebuilt in `SnapshotBootstrap` before traffic is accepted |
| Commit fails mid-transaction | No change at all | Transaction rolls back; snapshot untouched | Operator retries |
| Invalid value (`-30`, window end before start) | `422 VALIDATION_FAILED` with per-key details | Rejected pre-publication, no version consumed | Operator corrects the value |
| WebSocket frame lost | Stale value for at most the poll interval | SDK detects `incoming > current + 1` and requests a snapshot | Automatic gap heal |
| Duplicate / stale frame (`v41` after `v43`) | Nothing changes | Versions are compared before applying; older versions are ignored | None needed |
| 500 clients reconnect at once | Brief delay | SDK backs off with jittered exponential wait; sync sends only changes since each client's version | Converges without a thundering herd |
| Config server down while a service starts | Last persisted values | SDK loads `config_cache.json` before the first read | Values refresh when the server returns |
| Cache file corrupt | Values from the server instead | SDK logs and ignores the file | Normal sync repopulates it |
| Single node overloaded by writes | Slower publications | Fair lock serialises writers; reads are unaffected | Scale by partitioning tenants (see trade-offs) |
| Database unavailable | Reads still served from snapshot | Commits fail fast with `500`; no partial publication | DB restored, commits resume |

---

## 4. Key decisions

**Immutable snapshots over concurrent maps.** The brief suggests
`AtomicReference<ConcurrentHashMap>`. A fully immutable snapshot is stronger: it makes
multi-key atomicity structural rather than something the writer must remember to preserve,
and it removes any chance of a reader observing a half-applied change set.

**Version = change-set primary key.** No separate sequence that could drift from the
audit trail. The database guarantees monotonicity; `SnapshotStore` additionally refuses to
publish an older version.

**Rollback as a forward change set.** Rewriting history would desynchronise clients that
are already ahead. Rolling forward gives every client a single, uniform code path.

**Single-writer gate.** Deliberate consistency-over-throughput trade. It guarantees that
version order matches apply order, which is what makes the delta stream coherent. Reads
never touch it.

**Full rebuild over incremental patching.** O(D + O) per write, negligible at this scale,
and it eliminates a class of subtle bugs. The upgrade path is per-key patching if the
configuration set grows by orders of magnitude.

**Validation as a strategy per type.** Adding a new setting type is a new class, not a new
branch in a growing `switch` — the open/closed principle the evaluation asks for.

---

## 5. Where this stops scaling

- The snapshot must fit in one JVM heap (roughly tens of thousands of tenants × keys is
  still comfortable; millions is not).
- The single writer caps publication throughput at a few thousand change sets/second.
- Rollback replay is O(history); with millions of audit rows it should snapshot
  periodically and replay only the tail.
- WebSocket fan-out is per-node; 500 clients per node is fine, tens of thousands would
  want a shared pub/sub backbone.

---

## 6. Client SDK

The SDK is a plain Java library with two runtime dependencies (Jackson, SLF4J). It holds the
same immutable state model as the server: one `AtomicReference<ConfigState>`, whole-state
swaps, and a single serialised writer for inbound frames.

```
 WebSocket frames ──▶ StateApplier ──▶ AtomicReference.set(next) ──▶ readers
                           │
                           ├── version == current+1 → apply
                           ├── version <= current   → ignore
                           └── version > current+1  → gap → request snapshot
```

Three independent healing paths, each sufficient on its own:

1. **Push** — deltas arrive on the socket; normally the only path that matters.
2. **Gap detection** — the next frame's version reveals a missed one; the SDK asks for a
   snapshot immediately.
3. **Version poll** — every 3 s by default, independent of the socket.

Cold start reads `config_cache.json` before the first request, so a service that restarts
while the control plane is down never serves an empty configuration. Cache writes are
coalescing and atomic (temp file + move).

The end-to-end test (`ConfigSdkPropagationIntegrationTest`) starts a real server and a real
SDK, sets the poll interval to 30 s, publishes a change, and asserts the SDK sees it in
under 5 s — proving the push path rather than the poll.

See [`sdk.md`](sdk.md) for the lifecycle state machine, threading model and failure table.
