# Demo Script

A 12-minute walkthrough for an interviewer. Every command below is copy-pasteable on
Windows PowerShell; on macOS/Linux use `./mvnw` instead of `.\mvnw.cmd`.

---

## Before the call

```powershell
cd moveinsync
.\mvnw.cmd -DskipTests package
pwsh .\scripts\run-server.ps1 -SkipBuild
```

- Check `http://localhost:8080/actuator/health` returns `UP`.
- Keep `docs/benchmarks.md` open as a fallback.
- Demo keys: `demo-admin-key`, `demo-operator-key`, `demo-employee-key` (all acme except admin).

---

## 1. Read path (2 min)

```powershell
curl.exe -H "X-API-Key: demo-employee-key" "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=blr-office-1"
# value 30, sourceLevel OFFICE

curl.exe -H "X-API-Key: demo-employee-key" "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=hyd-office-1"
# value 45, sourceLevel CLIENT

curl.exe -H "X-API-Key: demo-employee-key" "http://localhost:8080/api/v1/config/explain?key=booking_cutoff_minutes&officeId=blr-office-1"
```

Talking point: *"Most-specific wins, and explain shows every level in order."*

## 2. Security and isolation (2 min)

```powershell
curl.exe http://localhost:8080/api/v1/config/version
# 401 UNAUTHENTICATED

curl.exe -H "X-API-Key: demo-employee-key" "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&clientId=globex-corp"
# 403 TENANT_FORBIDDEN

curl.exe -H "X-API-Key: demo-employee-key" http://localhost:8080/api/v1/config/snapshot
# only acme-corp rows

curl.exe -H "X-API-Key: demo-admin-key" http://localhost:8080/api/v1/config/snapshot
# both acme-corp and globex-corp
```

## 3. Atomic change set and rejection (2 min)

```powershell
curl.exe -X POST http://localhost:8080/api/v1/change-sets `
  -H "X-API-Key: demo-operator-key" -H "Content-Type: application/json" `
  -d '{"author":"operator-1","reason":"tighten night window","changes":[{"key":"night_safety_window","level":"OFFICE","entityId":"blr-office-1","value":"21:00-23:30"},{"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"25"}]}'
# 201, one new version, two entries
```

Now send a negative duration; it must be rejected **before** anything is stored:

```powershell
curl.exe -X POST http://localhost:8080/api/v1/change-sets `
  -H "X-API-Key: demo-operator-key" -H "Content-Type: application/json" `
  -d '{"author":"operator-1","reason":"bad","changes":[{"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"-30"}]}'
# 422 VALIDATION_FAILED with a per-key detail
curl.exe -H "X-API-Key: demo-admin-key" http://localhost:8080/api/v1/config/version
# unchanged
```

## 4. Canary then promote (2 min)

```powershell
curl.exe -X POST http://localhost:8080/api/v1/change-sets `
  -H "X-API-Key: demo-operator-key" -H "Content-Type: application/json" `
  -d '{"author":"operator-1","reason":"canary","changes":[{"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-1","value":"25"},{"key":"booking_cutoff_minutes","level":"OFFICE","entityId":"blr-office-2","value":"25"}]}'
# note the returned version, e.g. 3

curl.exe -H "X-API-Key: demo-employee-key" "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=del-office-1"
# still 45 - the canary did not reach Delhi

curl.exe -X POST -H "X-API-Key: demo-operator-key" "http://localhost:8080/api/v1/change-sets/3/promote?removeSourceOverrides=true&author=operator-1"
# new version, client-level value

curl.exe -H "X-API-Key: demo-employee-key" "http://localhost:8080/api/v1/config/resolve?key=booking_cutoff_minutes&officeId=del-office-1"
# now 25, sourceLevel CLIENT
```

## 5. Rollback (1 min)

```powershell
curl.exe -X POST http://localhost:8080/api/v1/rollbacks `
  -H "X-API-Key: demo-operator-key" -H "Content-Type: application/json" `
  -d '{"targetVersion":1,"author":"operator-1","reason":"revert demo","dryRun":true}'
# diff only, nothing published

curl.exe -X POST http://localhost:8080/api/v1/rollbacks `
  -H "X-API-Key: demo-operator-key" -H "Content-Type: application/json" `
  -d '{"targetVersion":1,"author":"operator-1","reason":"revert demo","dryRun":false}'
# published as a NEW version - history is never rewritten
```

## 6. Push at scale (2 min)

```powershell
java -jar config-benchmarks\target\config-benchmarks-0.1.0-SNAPSHOT-jar-with-dependencies.jar propagate --clients 200
```

Say while it bootstraps: *"Polling is 60 seconds, so only the WebSocket push can deliver the
change in the measured window."* Expected: `200/200`, p50 ~270 ms, p99 ~274 ms.

## 7. Kill the server (2 min)

```powershell
pwsh .\scripts\kill-server-test.ps1 -SkipBuild
```

Four beats: push observed → server killed → SDK still serving the last value with
`connected=false` → restart → reconnect at the same version. The change survived because the
commit is durable before the broadcast.

## 8. Numbers and code (2 min)

```powershell
java -jar config-benchmarks\target\config-benchmarks-0.1.0-SNAPSHOT-jar-with-dependencies.jar read
# ~19M resolves/sec, p50 100 ns, p99 200 ns
```

Then open, in this order:

1. `ConfigSnapshot.java` - immutable state, atomic swap, tenant-keyed index.
2. `ConfigChangeService.java` - validate → one transaction → swap → async broadcast.
3. `StateApplier.java` - the `+1 / ≤current / gap` rules.
4. `SecurityConfig.java` + `TenantGuard.java` - roles and tenant boundary.

Close with the concurrency tests (`SnapshotAtomicityTest`, `SdkAtomicityTest`) and the
push-only end-to-end test as evidence for the guarantees.

---

## Likely questions

| Question | Answer |
| --- | --- |
| v41 after v43? | `<= current` is stale and ignored; a gap (`> current+1`) requests a snapshot. |
| Why one writer? | Version order must match apply order or deltas become incoherent. |
| Why rebuild the snapshot? | O(D+O) per write, sub-millisecond, and it removes incremental-update bugs. |
| Where is isolation enforced? | API guard, tenant-tagged override data, per-session WebSocket filtering. |
| What is missing? | Flyway migrations, transport-level fault injection, delta retention, multi-node publication. |
| Docker? | Compose file supplied; Docker was not available on the build machine, so that path is unverified. |

## Fallbacks

- Port busy: `pwsh ./scripts/run-server.ps1 -SkipBuild -Port 8085`.
- Slow laptop: run propagation with `--clients 50`.
- Anything fails live: quote `docs/benchmarks.md` and the test report; do not debug on stage.
