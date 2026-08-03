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

Copy `infra/scripts/deploy.sh`, `backup.sh`, and `restore-drill.sh` to
`/opt/complyr/` and `chmod +x` them (the deploy workflow also rsyncs them on
every deploy).

## 6. DNS (Cloudflare)

Point these at the VPS (proxied, SSL mode "Full (strict)"):
`app`, `api`, `cdn`, `dev`, `api.dev`, `cdn.dev` under `complyr.eu`
*(placeholder — replace once the real domain is bought)*.

## 7. Backups (encrypted, off-site, drilled)

Dumps hold visitor PII + append-only consent evidence, so they are **encrypted
client-side** before they ever leave this host (ARCHITECTURE.md §8, CLAUDE.md
#3/#4). `backup.sh` pipes `pg_dump | gzip | age` (plaintext never hits disk),
writes a `.sha256` sidecar, ships to Hetzner Object Storage (EU), and rotates.

### 7.1 Install tooling

```bash
apt-get update && apt-get install -y age rclone
```

### 7.2 Generate the backup keypair (write-only model)

Backups are encrypted to a **public** key, so a compromised VPS can write but
never read them. Generate the keypair **on your workstation, not the server**:

```bash
age-keygen -o complyr-backup-identity.txt      # KEEP OFFLINE (password manager)
# The file prints "Public key: age1..." — that public key goes on the server.
```

**Prove the pair round-trips before trusting it.** With the write-only model a
typo'd/wrong public key still encrypts cleanly, silently producing backups that
can *never* be decrypted. Verify before putting the key into service:

```bash
PUB="$(grep -oE 'age1[0-9a-z]+' complyr-backup-identity.txt | head -1)"
echo "roundtrip-ok" | age -r "$PUB" | age -d -i complyr-backup-identity.txt
# must print exactly: roundtrip-ok
```

- Put **only** the `age1...` public key on the server (in `backup.env` below).
- Store `complyr-backup-identity.txt` (the private key) in your password
  manager. It is required **only** for a restore. Never commit it, never leave
  it on the VPS. Consider generating a second identity as a break-glass
  recipient and listing both public keys (space-separated) in `BACKUP_AGE_RECIPIENT`.

### 7.3 Configure the off-site remote (EU region)

```bash
rclone config    # new remote "hetzner-s3", type=s3, provider=Other,
                 # endpoint=https://<eu-region>.your-objectstorage.com  (EU!)
                 # then create a bucket, e.g. complyr-backups
```

Set a bucket **lifecycle rule** to expire objects after ~90 days as a
belt-and-suspenders backstop to the script's own off-site prune (the script may
not run if this host is the thing that failed).

### 7.4 Backup env + cron

Keep keys/remote out of the crontab and out of git — put them in a root-only env
file the cron sources:

```bash
umask 077
cat > /opt/complyr/backup.env <<'EOF'
BACKUP_AGE_RECIPIENT=age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
BACKUP_RCLONE_REMOTE=hetzner-s3:complyr-backups
# Optional overrides: BACKUP_LOCAL_RETENTION_DAYS, BACKUP_OFFSITE_RETENTION_DAYS, BACKUP_ENVS
EOF
chmod 600 /opt/complyr/backup.env

crontab -e -u root
# daily 03:15 — encrypted dump dev+prd, sha256, off-site to Hetzner (EU), rotate
15 3 * * * set -a; . /opt/complyr/backup.env; /opt/complyr/backup.sh >> /var/log/complyr-backup.log 2>&1
```

`backup.sh` **refuses to run** without `BACKUP_AGE_RECIPIENT` (never writes an
unencrypted dump) and exits non-zero on any failure so the log / uptime monitor
catches a broken backup.

### 7.5 Restore drill (do this before launch, then quarterly)

`restore-drill.sh` fetches a dump (newest local, or `--from-offsite <name>`),
verifies its checksum, decrypts + restores it into a **throwaway scratch DB**,
runs sanity queries, and drops it — production is never touched. It needs the
**private identity**, which is not on the server, so supply it out-of-band and
remove it afterwards:

```bash
# Create the target 0600 FIRST so scp can't briefly land it world-readable,
# then copy the private identity up just for the drill and remove it after.
ssh root@vps 'install -m600 /dev/null /dev/shm/id.txt'
scp complyr-backup-identity.txt root@vps:/dev/shm/id.txt
ssh root@vps
set -a; . /opt/complyr/backup.env; set +a
/opt/complyr/restore-drill.sh --env prd --identity /dev/shm/id.txt
# or from off-site (proves the OFF-SITE copy — do this one before launch):
#   /opt/complyr/restore-drill.sh --env prd --identity /dev/shm/id.txt --from-offsite complyr-prd-<ts>.sql.gz.age
rm -f /dev/shm/id.txt   # `shred` is ineffective on tmpfs (RAM-backed); rm is what removes it
```

`/dev/shm` is RAM-backed, so ensure the VPS has **swap disabled or encrypted** —
otherwise the identity could be paged to disk. The `--from-offsite` drill is a
**blocking** launch-checklist gate: it's the only thing that proves the recipient
key is decryptable AND the off-site copy is intact.

**Real recovery** uses the same guarded pipeline — verify the checksum, restore
into a **fresh** DB with `ON_ERROR_STOP`, then repoint the app (never stream an
unverified dump straight into the live `complyr` DB — a corrupt/truncated dump
would half-apply):

```bash
cd /opt/complyr/backups
sha256sum -c complyr-prd-<ts>.sql.gz.age.sha256          # abort if this fails
docker exec complyr-prd-postgres-1 psql -U complyr -d postgres -c 'CREATE DATABASE complyr_restored;'
age -d -i /dev/shm/id.txt complyr-prd-<ts>.sql.gz.age \
  | gunzip \
  | docker exec -i complyr-prd-postgres-1 psql -U complyr -d complyr_restored -v ON_ERROR_STOP=1
# verify complyr_restored, then repoint the app (POSTGRES_DB) or rename the DB.
```

## 8. Sanity checklist

- [ ] `curl -I https://api.dev.complyr.eu/actuator/health` → 200
- [ ] `curl -I https://dev.complyr.eu` → 200
- [ ] `curl -I https://cdn.dev.complyr.eu/v1.js` → 200 with `max-age=3600` (NOT immutable — deploys overwrite v1.js in place)
- [ ] Postgres port NOT reachable from outside (`nmap -p 5432 <ip>`)
- [ ] Backup cron produced an **encrypted** dump (`*.sql.gz.age` + `.sha256`), it appears in the EU off-site bucket, and `restore-drill.sh` PASSED
