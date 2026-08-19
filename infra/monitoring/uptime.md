# Uptime monitoring

Two layers, because an on-box script cannot detect its own box going down:

1. **External synthetic checks** (primary) — probe the public URLs from outside
   the servers. Catches a dead host, DNS/TLS breakage, and edge problems.
2. **On-box dead-man's-switch** (`uptime-check.sh`, secondary) — verifies app
   health *from inside* (dashboard up but API 5xx, DB unreachable) and pings an
   external heartbeat only when everything is green. If the host dies, the ping
   stops and the heartbeat service alerts — so this also backstops layer 1.

Both layers live on the **application** hosts. The database hosts are not in DNS
and expose nothing to probe (ADR-24), so they are covered indirectly: the API's
`/actuator/health` includes a database check, so a dead Postgres or a broken
private-network path surfaces as an API health failure within a minute. The one
database-host failure that is *not* visible this way is a backup that silently
stops running — §Layer 3 below closes that.

Both use **BetterStack** (or UptimeRobot / healthchecks.io) free tier. A monitor
pings public URLs and stores status only — no visitor PII — so it is not a data
processor under GDPR and the EU-residency rule (constraint #2) does not constrain
the choice. Do **not** feed it request bodies or logs.

## Layer 1 — external synthetic checks

Create three HTTP monitors (prd; add the `dev.` equivalents at a lower cadence):

| Monitor | URL | Expect | Interval |
|---------|-----|--------|----------|
| API health | `https://api.cookiekeeper.eu/actuator/health` | `200`, body contains `"status":"UP"` | 60s |
| Dashboard | `https://app.cookiekeeper.eu` | `200` | 60s |
| Widget CDN | `https://cdn.cookiekeeper.eu/v1.js` | `200`, `content-type: *javascript`, `cache-control: max-age=3600` | 300s |

- **Alerting:** email + (optional) a phone/Slack escalation on 2 consecutive
  failures, so a single blip doesn't page. Recovery notification on.
- **TLS expiry:** enable certificate-expiry alerts (Caddy auto-renews, but alert
  if it ever stalls).
- Keep the health monitor's expected-body assertion — a bare `200` can hide a
  degraded app that still serves the page shell.

## Layer 2 — on-box dead-man's-switch

`uptime-check.sh` curls the API health, the dashboard, and the widget CDN from
the **app host** itself; if **all** pass it pings `HEARTBEAT_URL`. Install it once
per app host, each with its own heartbeat URL — one shared heartbeat would let a
healthy dev keep production's alarm quiet. Configure a heartbeat
(a.k.a. "cron"/"heartbeat" monitor) in the same service with a grace period of
~3× the interval, then:

```bash
# /opt/cookiekeeper/monitoring.env  (root-only; NOT in git)
cat > /opt/cookiekeeper/monitoring.env <<'EOF'
HEARTBEAT_URL=https://uptime.betterstack.com/api/v1/heartbeat/xxxxxxxx
API_HEALTH_URL=https://api.cookiekeeper.eu/actuator/health
DASHBOARD_URL=https://app.cookiekeeper.eu
WIDGET_URL=https://cdn.cookiekeeper.eu/v1.js
EOF
chmod 600 /opt/cookiekeeper/monitoring.env

crontab -e -u root
# every minute — probe app health; ping the heartbeat only when all green
* * * * * set -a; . /opt/cookiekeeper/monitoring.env; /opt/cookiekeeper/uptime-check.sh >> /var/log/cookiekeeper-uptime.log 2>&1
```

Copy `infra/scripts/uptime-check.sh` to `/opt/cookiekeeper/` and `chmod +x` it (the
deploy workflow also rsyncs it). If a check fails, the script logs which one and
skips the heartbeat ping → the heartbeat monitor alerts after its grace period.

## Layer 3 — backup heartbeat (database hosts)

A database host can be perfectly healthy from the API's point of view while its
nightly backup has been failing for weeks — a full disk, an expired rclone
credential, a missing `age` binary. Nothing above notices, because nothing above
looks at the backup.

Add a **cron/heartbeat monitor** per database host with a grace period of ~26
hours (the job runs at 03:15 daily), and append the ping to the backup cron line
so it fires only on success:

```cron
15 3 * * * set -a; . /opt/cookiekeeper/backup.env; /opt/cookiekeeper/backup.sh >> /var/log/cookiekeeper-backup.log 2>&1 && curl -fsS -m 10 "$BACKUP_HEARTBEAT_URL" > /dev/null
```

The `&&` is load-bearing: `backup.sh` exits non-zero on any failure, so a failed
run skips the ping and the monitor alerts after its grace period. A ping issued
unconditionally would report "backups are fine" for exactly as long as they are
not.

Note this proves the job *ran and exited clean*, not that the dump is
restorable. Only the quarterly `restore-drill.sh --from-offsite` proves that
(DEPLOYMENT.md §11.3).

## Why this shape

- Layer 1 alone can't tell "API 500 but page still 200" from healthy; layer 2's
  body/DB assertions can.
- Layer 2 alone can't detect the box being down; the dead-man's-switch turns that
  into a *missing* heartbeat that layer 1's service catches.
- Neither sees a database host at all except through the API, which is enough for
  "is it serving?" and useless for "is it still being backed up?" — layer 3.
- Together: host-down, edge-down, app-degraded and backup-rotted are covered with
  free-tier monitors and two cron lines.
