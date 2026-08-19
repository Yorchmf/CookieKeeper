# Load smoke test + rate-limit tuning (CX22)

A pre-launch check that one environment's **CX22 pair** (2 vCPU / 4 GB each) —
an application host running the API JVM, the dashboard and the scanner, plus a
dedicated Postgres host reached over the private network (ADR-24) — holds a
realistic sustained load without 5xx or runaway latency, and that the rate
limits are sized correctly for those boxes.

Since the split there are two boxes to watch, and the interesting question
changed: the app host and the database host now compete for nothing, so a bend
under load points at one of them specifically rather than at "the box".

This is a **smoke** test (does it stand up, where does it bend), not a full
capacity benchmark. It is enough to sign off the launch-checklist item and to
ground the tuning numbers below.

## Run it (DEV ONLY)

```bash
# Install k6: https://k6.io/docs/get-started/installation/  (apt: `k6`)
k6 run -e BASE_URL=https://api.dev.cookiekeeper.eu \
       -e SITE_KEY=<a-real-dev-site-key> \
       -e PUBLIC_ID=<a-real-dev-policy-public-id> \
       -e RPS=40 \
       infra/load/smoke.js
```

> **Never run against prd.** The consent path writes append-only audit rows
> (CLAUDE.md #3); the scan path spawns Chromium crawls. This script deliberately
> exercises only safe **reads** (`widget-config`, `/actuator/health`, hosted
> policy). Point it at dev, whose data is disposable.

Watch **both servers** while it runs (not k6). Two terminals:

```bash
# app host — containers, CPU, memory
ssh root@<dev app ip> 'docker stats --no-stream; uptime'

# database host — active backends and any waiting
ssh root@<dev db ip> 'runuser -u postgres -- psql -d cookiekeeper -c \
  "SELECT state, wait_event_type, count(*) FROM pg_stat_activity \
   WHERE backend_type = $$client backend$$ GROUP BY 1,2 ORDER BY 3 DESC;"; uptime'
```

Reading the pair together is the point. Active backends pinned at `DB_POOL_MAX`
with the app host's CPU idle means the pool is the ceiling. App host CPU at 100%
with a mostly-idle database means Tomcat threads or the JVM heap. Both busy means
you have found the real knee.

## Reading the result

- `http_req_failed rate<0.01` and `http_req_duration{kind:read} p95<500ms` must
  pass. Reads are the hot path (every page load fetches `widget-config`); if they
  bend, one of the two hosts is the bottleneck — the `pg_stat_activity` sample
  above says which.
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
| DB pool size | `DB_POOL_MAX` | 10 | Postgres on 2 vCPU peaks at a handful of active connections; each idle one costs memory *on the database host*. Keep the **sum across all services on the app host** (API + scanner) under that host's `max_connections`. Since ADR-24 each environment has its own database server, so the sum is per-environment — it no longer has to leave room for the other environment's JVMs. |
| DB min idle | `DB_POOL_MIN_IDLE` | 2 | |
| DB connect timeout | `DB_POOL_CONNECTION_TIMEOUT_MS` | 10000 | Fail a request fast when the pool is exhausted rather than hang. |

Per-IP / per-user rate-limit tiers (`cookiekeeper.rate-limit.*`,
`RateLimit` in `CookieKeeperProperties.kt`) are a **correctness/abuse** control, not a
capacity control — Cloudflare at the edge is the volumetric backstop. Only revisit
a tier if the smoke test shows legitimate traffic tripping it (e.g. a large NAT'd
office sharing one IP on the consent tier). Current tiers: auth 10, consent 120,
public-scan 10, public-policy 120 (per IP/min); billing 20, general 300 (per
user/min).

## When to re-run

- Before every launch / major release.
- After changing thread/pool defaults, or resizing either host (ARCHITECTURE.md
  §11 step 1) — resize one at a time, so the result attributes to a machine.
- After anything that changes the private-network path to the database (TLS
  settings, `EGRESS_DB_TARGETS`, `pg_hba.conf`): the hop is now a network hop,
  and a per-query latency regression there shows up as read p95, not as an error.
