# Config Console — React Dashboard

A small React app that exercises the configuration server from a browser: resolve a
setting through the hierarchy, publish an atomic change set, promote a canary, roll back,
and watch live pushes arrive over WebSocket.

It is a demo client, not part of the production path — the real read path is the embedded
Java SDK.

---

## Run it

From the repository root, one command starts both the server and the dashboard:

```powershell
pwsh ./scripts/run-demo.ps1
```

Then open <http://localhost:5173>.

Or run them separately:

```bash
# terminal 1 — server
./mvnw -DskipTests package
java -jar config-server/target/config-server-0.1.0-SNAPSHOT.jar

# terminal 2 — dashboard
cd config-ui
npm install
npm run dev
```

Requires **Node 18+** (built and tested on Node 24). The dashboard proxies `/api` and `/ws`
to the server, so no CORS configuration is needed; point it elsewhere with
`CONFIG_SERVER_URL=http://host:port npm run dev`.

---

## What you can do

| Panel | What it demonstrates |
| --- | --- |
| **Role selector** (top bar) | Swap between Admin / Operator / Employee / Globex keys. The snapshot panel changes with it — that is tenant isolation, enforced server-side. |
| **Resolve a setting** | Pick a setting, client, office and shift. Shows the effective value and which level supplied it. *Explain* renders the full precedence trace with the winning level highlighted. |
| **Publish a change set** | Add several rows; they all land under one version. A blank value deletes the override. Try `-30` minutes to see the 422 rejection with the server's own message. |
| **Canary, promote, roll back** | Promote a staged version to the whole client, or preview and apply a rollback with a diff. |
| **Visible overrides** | The snapshot the current API key is allowed to see, grouped by level and entity. |
| **Activity feed** | Reads, writes, rejections and live `PUSH` frames as they arrive, with the version number. |

The live indicator in the top bar shows the WebSocket state (Live / Connecting /
Reconnecting) and attempts to self-heal with jittered backoff, mirroring the Java SDK.

---

## A five-minute demo path

1. As **Operator**, resolve `booking_cutoff_minutes` for `blr-office-1` → **30**, from OFFICE.
2. Resolve the same key for `hyd-office-1` → **45**, from CLIENT. Then click *Explain*.
3. Click **Prefill two-key example** → **Publish change set** → version 2, two entries.
   Watch the Activity feed record the write and then the push.
4. Set the last value to `-30` and publish → **422**, with the per-key reason. Version stays 2.
5. Switch to **Globex operator** → the acme offices disappear from the overrides panel.
6. Back on Operator: **Roll back** to version 1, *Preview diff*, then *Apply*.

---

## How it talks to the server

- REST calls go through `src/api.js`, which sets `X-API-Key` and normalises errors into
  `ApiError` objects (`status`, `code`, `details`).
- The WebSocket in `src/useLiveStream.js` connects to `/ws/config?apiKey=…` — browsers
  cannot set headers on a WebSocket handshake, and the server accepts the key as a query
  parameter. A wrong key is rejected during the upgrade.
- On a `delta` frame the app refreshes the snapshot, so the side panel always reflects
  server state rather than locally guessing what changed.

---

## Build

```bash
npm run build     # static bundle in dist/
npm run preview   # serve the built bundle
```

The built bundle still needs `/api` and `/ws` proxied to the server, so for a static
deployment put both behind the same reverse proxy (or serve `dist/` from anything that can
forward those two paths).

---

## Verified flows

`e2e-check.mjs` drives the running dashboard with Playwright and asserts 17 behaviours:
initial render, WebSocket connection, resolve, explain, atomic publish, live push in the
feed, overrides refresh, validation rejection, tenant isolation across roles, rollback
preview, and a clean console.

```bash
node e2e-check.mjs   # server + dashboard must be running
```

Playwright is intentionally not a project dependency; install it ad hoc with
`npm install --no-save playwright && npx playwright install chromium` before running the
check.
