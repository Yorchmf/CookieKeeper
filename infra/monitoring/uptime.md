# Uptime monitoring

Two layers, because an on-box script cannot detect its own box going down:

1. **External synthetic checks** (primary) — probe the public URLs from outside
   the VPS. Catches a dead box, DNS/TLS breakage, and edge problems.
2. **On-box dead-man's-switch** (`uptime-check.sh`, secondary) — verifies app
   health *from inside* (dashboard up but API 5xx, DB unreachable) and pings an
   external heartbeat only when everything is green. If the VPS dies, the ping
   stops and the heartbeat service alerts — so this also backstops layer 1.

Both use **BetterStack** (or UptimeRobot / healthchecks.io) free tier. A monitor
pings public URLs and stores status only — no visitor PII — so it is not a data
processor under GDPR and the EU-residency rule (constraint #2) does not constrain
the choice. Do **not** feed it request bodies or logs.

## Layer 1 — external synthetic checks

Create three HTTP monitors (prd; add the `dev.` equivalents at a lower cadence):

| Monitor | URL | Expect | Interval |
|---------|-----|--------|----------|
| API health | `https://api.complyr.eu/actuator/health` | `200`, body contains `"status":"UP"` | 60s |
| Dashboard | `https://app.complyr.eu` | `200` | 60s |
| Widget CDN | `https://cdn.complyr.eu/v1.js` | `200`, `content-type: *javascript`, `cache-control: max-age=3600` | 300s |

- **Alerting:** email + (optional) a phone/Slack escalation on 2 consecutive
  failures, so a single blip doesn't page. Recovery notification on.
- **TLS expiry:** enable certificate-expiry alerts (Caddy auto-renews, but alert
  if it ever stalls).
- Keep the health monitor's expected-body assertion — a bare `200` can hide a
  degraded app that still serves the page shell.

## Layer 2 — on-box dead-man's-switch

`uptime-check.sh` curls the API health, the dashboard, and the widget CDN from
the VPS itself; if **all** pass it pings `HEARTBEAT_URL`. Configure a heartbeat
(a.k.a. "cron"/"heartbeat" monitor) in the same service with a grace period of
~3× the interval, then:

```bash
# /opt/complyr/monitoring.env  (root-only; NOT in git)
cat > /opt/complyr/monitoring.env <<'EOF'
HEARTBEAT_URL=https://uptime.betterstack.com/api/v1/heartbeat/xxxxxxxx
API_HEALTH_URL=https://api.complyr.eu/actuator/health
DASHBOARD_URL=https://app.complyr.eu
WIDGET_URL=https://cdn.complyr.eu/v1.js
EOF
chmod 600 /opt/complyr/monitoring.env

crontab -e -u root
# every minute — probe app health; ping the heartbeat only when all green
* * * * * set -a; . /opt/complyr/monitoring.env; /opt/complyr/uptime-check.sh >> /var/log/complyr-uptime.log 2>&1
```

Copy `infra/scripts/uptime-check.sh` to `/opt/complyr/` and `chmod +x` it (the
deploy workflow also rsyncs it). If a check fails, the script logs which one and
skips the heartbeat ping → the heartbeat monitor alerts after its grace period.

## Why this shape

- Layer 1 alone can't tell "API 500 but page still 200" from healthy; layer 2's
  body/DB assertions can.
- Layer 2 alone can't detect the box being down; the dead-man's-switch turns that
  into a *missing* heartbeat that layer 1's service catches.
- Together: box-down, edge-down, and app-degraded are all covered with two free
  monitors and one cron line.
