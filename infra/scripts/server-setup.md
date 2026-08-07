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

Note this only covers **inbound**. `ufw` filters the host's own traffic; container
traffic is forwarded, not local, so `default allow outgoing` says nothing about
what a container may reach. Container egress is §2.1 — do it after Docker is
installed and the networks exist.

## 2.1 Container egress firewall (ADR-18, blocking gate)

The scanner crawls domains nobody has proved they own. `ScanTargetValidator`
resolves and rejects private targets in the app, but it cannot win a DNS-rebind
race against the browser's own resolution — so the *packet-level* denial is the
guarantee, and ADR-18 makes it a blocking deploy requirement.

`egress-firewall.sh` installs **two** chains, because one hook is not enough:

| Chain | Hooked into | Covers |
|-------|-------------|--------|
| `COMPLYR-EGRESS-{A,B}` | `DOCKER-USER` | container → anywhere else (routed traffic) |
| `COMPLYR-EGRESS-HOST-{A,B}` | `INPUT` position 1 | container → **this host** |

The `INPUT` half is not optional. A packet addressed to a host-local address —
the bridge gateway (host sshd), any public address of the box, anything
`docker-proxy` publishes on `0.0.0.0` — is delivered locally and *never reaches
`FORWARD`*, so `DOCKER-USER` rules cannot see it, let alone drop it. Without this
chain a container can ssh to the host, and can aim a crawl at this box's own
public address (or any hostname pointed straight at it) and land back inside
Caddy. The jump must sit at `INPUT` position 1, above ufw's chains, or
`ufw allow 22` wins — the script inserts it there.

> `ufw reload` (and `ufw enable`) rebuilds `INPUT` from ufw's own rule set and
> takes our jump with it. That is not a crisis — the timer reapplies within two
> minutes — but if you have just touched ufw, run `apply` rather than waiting.

Net effect: a container may reach the public internet and the containers its own
environment wires it to. Cloud metadata (`169.254.169.254`), RFC1918, CGNAT,
loopback, this host, the *other* environment and everything else behind the
shared Caddy are unreachable.

Two properties worth knowing before you change anything:

- **The DROPs match the bridge interface (`br-+`), not a source subnet.** A
  network that is renamed, renumbered or created behind our back is filtered too
  — unknown traffic fails closed instead of falling through to the internet.
  Only the narrow RETURN exemptions are subnet-scoped, and those subnets are
  pinned in `infra/compose.*.yml` and in `docker network create` (§4). Keep the
  script's `MESH_SUBNETS` / `INGRESS_SUBNETS` defaults in sync with them.
- **`caddy-net` is shared by dev and prd, so only Caddy may open connections on
  it** (`10.31.30.2`, pinned in `compose.caddy.yml`). Upstreams answer Caddy and
  never initiate. A blanket intra-subnet allow there would let a rebound scanner
  reach every vhost — and let dev containers reach prd ones. Note what this is
  worth: the exemption keys on a *source address* on a shared L2 segment, so a
  container with `CAP_NET_RAW` could forge it. It raises the cost of dev→prd
  lateral movement; it is not a trust boundary you may lean on. The real
  boundary is that the two environments share no other network and no
  credentials.

Install it **root-owned and outside `/opt/complyr`**, which CI rsyncs into: the
deploy key must not be able to rewrite the thing that constrains it.

```bash
# from a repo checkout on the server (or scp the three files up)
install -o root -g root -m 0755 infra/scripts/egress-firewall.sh \
  /usr/local/sbin/complyr-egress-firewall
install -o root -g root -m 0644 infra/scripts/complyr-egress-firewall.service \
  infra/scripts/complyr-egress-firewall.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now complyr-egress-firewall.service complyr-egress-firewall.timer
```

The service is `WantedBy=docker.service` because a docker daemon restart
recreates `DOCKER-USER` and drops the jump; the timer re-applies on a wall-clock
schedule every 2 min, which also retries a run that *failed* (dockerd rewrites
iptables constantly, so an xtables lock collision is routine). Applying is
idempotent and atomic: the rules are built into a spare chain and the jump moves
only once the build succeeded, so a half-finished run leaves the previous rules
in force rather than an empty chain that filters nothing.

Prove it, don't assume it — `verify` attaches a throwaway container to each
filtered network and tries the connections that must fail *and* the ones that
must work. Every negative probe targets something that genuinely answers with
the firewall removed (host sshd, this box's TLS port, the other environment's
postgres), so it cannot pass vacuously; the positive probes catch an
over-blocking rule set, which breaks every crawl and is just as bad:

```bash
/usr/local/sbin/complyr-egress-firewall verify
```

Host-level prerequisites the rules depend on:

- **DNS must not be private.** Containers resolve via docker's embedded server,
  which forwards from inside the container — a private upstream would be dropped
  by these rules. Use the EU public resolvers (also the right answer for data
  residency) in `/etc/docker/daemon.json`, then `systemctl restart docker`:
  `{"dns": ["185.12.64.1", "185.12.64.2"]}`. `verify` asserts resolution still
  works from inside each filtered network.
- **`net.bridge.bridge-nf-call-iptables=1`** (the default once `br_netfilter` is
  loaded, which docker does — set it in `/etc/sysctl.d/` so it survives a module
  reload). Container-to-container frames *on the same bridge* only reach
  netfilter when this is on; with it off the `caddy-net` policy is silently not
  enforced and nothing looks any different. `verify` asserts it.
- **`"userland-proxy": false`** in the same `daemon.json`. Not load-bearing —
  the `INPUT` chain already blocks a container from reaching a published port on
  the host — but with the proxy on, that traffic is re-originated from the host
  namespace and shows up as the host talking to itself, which is confusing to
  audit. Off, published ports are pure DNAT and stay visible in `FORWARD`.
- **`net.netfilter.nf_conntrack_helper=0`** (the modern default — assert it in
  `/etc/sysctl.d/`). With helpers on, a hostile server the scanner connects to
  can create a conntrack expectation and get a `RELATED` exemption to an
  arbitrary address. The rules only exempt `RELATED` for ICMP, but the sysctl is
  the belt to that suspenders.
- **Leave IPv6 off on the docker networks** (the default). The script installs a
  v6 backstop that drops all forwarded/host-bound traffic from docker bridges,
  so enabling v6 fails closed rather than routing silently around the v4 rules —
  but the supported configuration is still v4-only.

## 3. Install Docker

```bash
curl -fsSL https://get.docker.com | sh
```

## 4. Directory layout + shared network

`caddy-net` gets a pinned subnet for the same reason the compose files pin
theirs — the egress rules' RETURN exemptions are written per subnet (§2.1), and
an auto-assigned one moves under a `compose up`.

`--ip-range` matters as much as `--subnet`. Docker's dynamic pool otherwise
starts at `.2` — the address `compose.caddy.yml` pins for Caddy and the one the
egress rules exempt. Whichever container comes up first would take it, and then
Caddy fails to start (`Address already in use`, exit 125) *or*, worse, some
other container is holding the one address allowed to initiate connections on
this network. Confining the pool to the upper half keeps `.2` reserved:

```bash
mkdir -p /opt/complyr/{dev,prd,caddy,backups}
mkdir -p /srv/cdn/{dev,prd}
docker network create --subnet 10.31.30.0/24 --ip-range 10.31.30.128/25 caddy-net
```

If `caddy-net` already exists without an `--ip-range`, recreate it — the setting
cannot be changed in place. Stop everything attached (`docker compose down` in
`/opt/complyr/{dev,prd,caddy}`), `docker network rm caddy-net`, run the command
above, then bring the stacks back up.

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

Copy `infra/scripts/deploy.sh`, `backup.sh`, `restore-drill.sh`, and
`uptime-check.sh` to `/opt/complyr/` and `chmod +x` them (the deploy workflow
also rsyncs `deploy.sh` on every deploy). `egress-firewall.sh` is deliberately
**not** among them — it lives root-owned in `/usr/local/sbin` (§2.1), out of
reach of anything the deploy key can write.

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
- [ ] Container egress firewall installed and **`complyr-egress-firewall verify` PASSED** (ADR-18 blocking gate — metadata / host sshd / this box's public IP / the other environment's postgres all closed; public internet and DNS still open)
- [ ] `daemon.json` has the EU resolvers **and** `"userland-proxy": false`; `net.bridge.bridge-nf-call-iptables=1` and `net.netfilter.nf_conntrack_helper=0` in `/etc/sysctl.d/`; IPv6 off on all docker networks (§2.1 prerequisites)
- [ ] `caddy-net` was created with `--ip-range 10.31.30.128/25` so Caddy keeps `.2` (`docker network inspect caddy-net` → `IPRange` set; §4)
- [ ] Backup cron produced an **encrypted** dump (`*.sql.gz.age` + `.sha256`), it appears in the EU off-site bucket, and `restore-drill.sh` PASSED
- [ ] Load smoke test (`infra/load/README.md`) run against dev — reads p95 < 500ms, no 5xx; thread/pool defaults confirmed for the CX22
- [ ] Uptime monitoring live (`infra/monitoring/uptime.md`): external checks on api health + dashboard + widget CDN, and `uptime-check.sh` heartbeat cron pinging green
