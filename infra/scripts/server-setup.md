# Server Setup Runbook (one-time)

Provision the single Hetzner VPS that runs both `complyr-dev` and
`complyr-prd` (ARCHITECTURE.md §3, §6). Placeholder domain: `complyr.eu`.

## 1. Create the VPS

- Hetzner Cloud → CX22 (2 vCPU / 4GB), location **Falkenstein or Nuremberg**
  (EU residency is a product feature), Ubuntu 24.04 LTS.
- Add your personal SSH key at creation. Enable Hetzner daily snapshots/backups.

## 2. Firewall

Hetzner Cloud Firewall (or `ufw`) — allow inbound **22, 80, 443** only:

```bash
ufw default deny incoming && ufw default allow outgoing
ufw allow 22/tcp && ufw allow 80/tcp && ufw allow 443/tcp
ufw enable
```

Harden SSH: `PasswordAuthentication no`, `PermitRootLogin prohibit-password`
in `/etc/ssh/sshd_config`, then `systemctl reload ssh`.

## 3. Install Docker

```bash
curl -fsSL https://get.docker.com | sh
```

## 4. Directory layout + shared network

```bash
mkdir -p /opt/complyr/{dev,prd,caddy,backups}
mkdir -p /srv/cdn/{dev,prd}
docker network create caddy-net
```

Each env dir gets: its compose file (rsynced by CI), a hand-maintained `.env`
(copy from repo `.env.example`, fill real secrets), and `.env.deploy`
(written by `deploy.sh`). `/opt/complyr/caddy` gets `compose.caddy.yml` +
`Caddyfile` from `infra/caddy/`.

```bash
cd /opt/complyr/caddy
docker compose -f compose.caddy.yml --project-name complyr-caddy up -d
```

## 5. Deploy key (GitHub Actions → server)

```bash
adduser --disabled-password deploy
usermod -aG docker deploy
mkdir -p ~deploy/.ssh
ssh-keygen -t ed25519 -f /tmp/complyr-deploy -N ''   # run locally, not on server
# put the .pub into ~deploy/.ssh/authorized_keys
chown -R deploy:deploy /opt/complyr /srv/cdn
```

GitHub repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|--------|-------|
| `SSH_HOST` | VPS IP or hostname |
| `SSH_USER` | `deploy` |
| `SSH_KEY`  | the ed25519 **private** key |

Copy `infra/scripts/deploy.sh` and `backup.sh` to `/opt/complyr/` and
`chmod +x` them (the deploy workflow also rsyncs them on every deploy).

## 6. DNS (Cloudflare)

Point these at the VPS (proxied, SSL mode "Full (strict)"):
`app`, `api`, `cdn`, `dev`, `api.dev`, `cdn.dev` under `complyr.eu`
*(placeholder — replace once the real domain is bought)*.

## 7. Backups (cron)

```bash
crontab -e -u root
# daily at 03:15 — pg_dump dev+prd, gzip, 30-day rotation, optional rclone off-site
15 3 * * * /opt/complyr/backup.sh >> /var/log/complyr-backup.log 2>&1
```

**Test a restore before launch** (ARCHITECTURE.md §8):

```bash
gunzip -c /opt/complyr/backups/complyr-prd-<ts>.sql.gz \
  | docker exec -i complyr-dev-postgres-1 psql -U complyr -d complyr
```

## 8. Sanity checklist

- [ ] `curl -I https://api.dev.complyr.eu/actuator/health` → 200
- [ ] `curl -I https://dev.complyr.eu` → 200
- [ ] `curl -I https://cdn.dev.complyr.eu/v1.js` → 200 with `max-age=3600` (NOT immutable — deploys overwrite v1.js in place)
- [ ] Postgres port NOT reachable from outside (`nmap -p 5432 <ip>`)
- [ ] Backup cron ran and produced a dump; restore drill done
