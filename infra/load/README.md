# Load smoke test + rate-limit tuning (CX22)

A pre-launch check that the single Hetzner **CX22 (2 vCPU / 4GB)** — which runs
`complyr-dev` **and** `complyr-prd` side by side (two API JVMs, two Postgres,
two dashboards, the scanner) — holds a realistic sustained load without 5xx or
runaway latency, and that the rate limits are sized correctly for that box.

This is a **smoke** test (does it stand up, where does it bend), not a full
capacity benchmark. It is enough to sign off the launch-checklist item and to
ground the tuning numbers below.

## Run it (DEV ONLY)

```bash
# Install k6: https://k6.io/docs/get-started/installation/  (apt: `k6`)
k6 run -e BASE_URL=https://api.dev.complyr.eu \
       -e SITE_KEY=<a-real-dev-site-key> \
       -e PUBLIC_ID=<a-real-dev-policy-public-id> \
       -e RPS=40 \
       infra/load/smoke.js
```

> **Never run against prd.** The consent path writes append-only audit rows
> (CLAUDE.md #3); the scan path spawns Chromium crawls. This script deliberately
> exercises only safe **reads** (`widget-config`, `/actuator/health`, hosted
> policy). Point it at dev, whose data is disposable.

Watch the **server** while it runs (the box, not k6):

```bash
ssh root@vps 'docker stats --no-stream; docker exec complyr-dev-postgres-1 \
  psql -U complyr -d complyr -c "SELECT count(*) FROM pg_stat_activity WHERE state=$$active$$;"'
```

## Reading the result

- `http_req_failed rate<0.01` and `http_req_duration{kind:read} p95<500ms` must
  pass. Reads are the hot path (every page load fetches `widget-config`); if they
  bend, the box is the bottleneck.
- `rate_limited_responses` reports how often the per-IP limiter fired on the
  policy endpoint. **429s are expected and correct** here — a single k6 host is a
  single source IP, and the public read tiers cap at ~2 req/s/IP. This validates
  that the limiter *engages*; it is not a capacity failure.
- To find the real ceiling, re-run with rising `RPS` (40 → 80 → 160) until k6
  can't sustain the arrival rate or `http_req_duration` climbs. That knee is your
  per-box capacity; keep prd's expected peak comfortably below it.

## Tuning knobs (all env-overridable — no rebuild)

The load ceiling on a 2 vCPU box is **Tomcat threads + the DB pool**, not the
per-IP rate limits. Defaults are sized for the CX22 and set in
`backend/src/main/resources/application.yml`; override per-env in the server
`.env` and re-test.

| Knob | Env var | Default | Rationale |
|------|---------|---------|-----------|
| Tomcat worker threads | `SERVER_TOMCAT_MAX_THREADS` | 50 | Framework default (200) wastes ~200 thread stacks per JVM on a shared 2 vCPU box; requests serialize on the DB pool anyway. 50 leaves headroom above the pool for cache-served reads. |
| Min spare threads | `SERVER_TOMCAT_MIN_SPARE_THREADS` | 5 | Keep a few warm for burst latency. |
| Accept backlog | `SERVER_TOMCAT_ACCEPT_COUNT` | 100 | Short spikes queue here instead of being refused; sustained overload sheds fast. |
| Max connections | `SERVER_TOMCAT_MAX_CONNECTIONS` | 2000 | Upper bound on in-flight connections. |
| DB pool size | `DB_POOL_MAX` | 10 | Postgres on 2 vCPU peaks at a handful of active connections; each idle one costs memory. Keep the **sum across all services** (2× API + scanner) under Postgres `max_connections` (default 100). |
| DB min idle | `DB_POOL_MIN_IDLE` | 2 | |
| DB connect timeout | `DB_POOL_CONNECTION_TIMEOUT_MS` | 10000 | Fail a request fast when the pool is exhausted rather than hang. |

Per-IP / per-user rate-limit tiers (`complyr.rate-limit.*`,
`RateLimit` in `ComplyrProperties.kt`) are a **correctness/abuse** control, not a
capacity control — Cloudflare at the edge is the volumetric backstop. Only revisit
a tier if the smoke test shows legitimate traffic tripping it (e.g. a large NAT'd
office sharing one IP on the consent tier). Current tiers: auth 10, consent 120,
public-scan 10, public-policy 120 (per IP/min); billing 20, general 300 (per
user/min).

## When to re-run

- Before every launch / major release.
- After changing thread/pool defaults, the VPS size, or moving prd to its own box
  (ARCHITECTURE.md §11 step 1).
