# Server Setup Runbook (one-time)

Four Hetzner machines: an **application** host and a dedicated **database** host
per environment (ADR-24). Placeholder domain: `cookiekeeper.eu`.

| Host | Runs | Public DNS | Private IP |
|------|------|-----------|------------|
| `cookiekeeper-dev-app` | Docker: api, scanner, dashboard, mailpit, Caddy | `dev`, `api.dev`, `cdn.dev` | `10.20.10.10` |
| `cookiekeeper-dev-db`  | bare Postgres 16 + backups | none | `10.20.10.20` |
| `cookiekeeper-prd-app` | Docker: api, scanner, dashboard, Caddy | `app`, `api`, `cdn`, apex | `10.20.20.10` |
| `cookiekeeper-prd-db`  | bare Postgres 16 + backups | none | `10.20.20.20` |

The two environments sit on **separate Hetzner private networks**, so dev has no
route to prd's database at all. The database hosts have a public IP for SSH and
off-site backup egress only — no service listens on it.

This runbook has two tracks. §1 is common, then **§2 is the database hosts** and
**§3–§6 the application hosts**. Do the database host first: the app stack cannot
start without a reachable database and its certificate.

## 1. Provision (Terraform, by hand)

`infra/terraform/platform/` owns all four servers, both private networks, the
cloud firewalls and the zone-wide Cloudflare settings. It is applied **by hand
only** — no pipeline plans or applies it (the release pipelines read its state
read-only, for the app hosts' public IPs).

```bash
cd infra/terraform/platform
cp terraform.tfvars.example terraform.tfvars   # fill in: ssh_public_key, ci_deploy_public_key, cloudflare_zone_id
export HCLOUD_TOKEN=...  CLOUDFLARE_API_TOKEN=...
terraform init && terraform plan -out=platform.tfplan
terraform apply platform.tfplan
terraform output servers        # public IPs + private IPs of all four
```

Cloud-init does the parts that need no secrets and no judgement: OS hardening,
ufw, Docker (app hosts), Postgres 16 with its config/`pg_hba`/TLS keypair (db
hosts). Everything below is the rest — the parts that involve a credential, a
decision, or a file that must never appear in Terraform state.

Generate the CI deploy key **on your workstation** before the first apply; its
public half is a Terraform variable, its private half becomes a GitHub secret
(§6):

```bash
ssh-keygen -t ed25519 -f cookiekeeper-deploy -N ''
```

---

# Database hosts

## 2. Database host bring-up (`cookiekeeper-{dev,prd}-db`)

Do this on **each** database host. Nothing here is shared between them: separate
passwords, separate certificates, separate backup prefixes.

### 2.1 Set the `cookiekeeper` role password

Cloud-init creates the role and the database **without a password**, on purpose:
every Terraform variable is stored in state in plaintext, and `user_data` is a
variable. With the scram-sha-256-only `pg_hba.conf`, a passwordless role cannot
authenticate — the host fails closed until you do this by hand.

```bash
ssh root@<db public ip>
pw="$(openssl rand -base64 32)"          # keep it; it goes in the app host's .env
runuser -u postgres -- psql -v ON_ERROR_STOP=1 \
  -c "ALTER ROLE cookiekeeper PASSWORD '$pw';"
printf 'DB_PASSWORD=%s\n' "$pw"          # copy into /opt/cookiekeeper/<env>/.env on the APP host
```

Store it in your password manager. It is the one credential that reads every
table in the product, and it exists in exactly two places: the database's own
`pg_authid`, and the app host's `chmod 600` `.env`.

### 2.2 Hand the TLS certificate to the app host

Postgres serves TLS with a self-signed certificate that is **its own CA**, with an
IP SAN for the private address. The app connects with `sslmode=verify-ca` and
pins that exact certificate — so it needs a copy. (`verify-full` would require the
certificate to match a hostname; the JDBC URL names an IP.)

```bash
# from your workstation
scp root@<db public ip>:/etc/postgresql/ssl/server.crt ./pgca-<env>.crt
scp ./pgca-<env>.crt deploy@<app public ip>:/opt/cookiekeeper/<env>/pgca.crt
```

The certificate is public information — it is what the server presents on every
connection. The **key** (`server.key`) never leaves the database host.

Sanity-check the pinning from the app host once its stack is up (§4):

```bash
psql "postgresql://cookiekeeper@10.20.10.20:5432/cookiekeeper?sslmode=verify-ca&sslrootcert=/opt/cookiekeeper/dev/pgca.crt" -c 'SELECT 1'
```

### 2.3 Confirm the four layers actually hold

A Hetzner cloud firewall filters **public** traffic only — it cannot see the
private network, which is exactly where 5432 lives. So the controls that matter
are all on this host, and all four are worth verifying rather than assuming:

```bash
runuser -u postgres -- psql -Atc 'SHOW listen_addresses'   # localhost,10.20.x.20 — never 0.0.0.0
ufw status | grep 5432                                     # ALLOW from the app host's private IP only
grep -c '^host ' /etc/postgresql/16/main/pg_hba.conf       # 0 — every network line is hostssl
nmap -Pn -p 5432 <db public ip>                            # closed/filtered from outside
```

If `pg_hba.conf` ever needs another source, add another `hostssl` line scoped to
one `/32`. Never a bare `host` line: `hostssl` makes the server *refuse* a
plaintext connection rather than merely prefer TLS.

Backups also run on this host — §7.

---

# Application hosts

## 3. Container egress firewall (ADR-18, blocking gate)

App hosts only. The scanner crawls domains nobody has proved they own.
`ScanTargetValidator` resolves and rejects private targets in the app, but it
cannot win a DNS-rebind race against the browser's own resolution — so the
*packet-level* denial is the guarantee, and ADR-18 makes it a blocking deploy
requirement. The split raised the stakes: the database is now a **network** peer,
so a rebound crawl aimed at `10.20.x.20` is a plausible route to it.

`egress-firewall.sh` installs **two** chains, because one hook is not enough:

| Chain | Hooked into | Covers |
|-------|-------------|--------|
| `COOKIEKEEPER-EGRESS-{A,B}` | `DOCKER-USER` | container → anywhere else (routed traffic) |
| `COOKIEKEEPER-EGRESS-HOST-{A,B}` | `INPUT` position 1 | container → **this host** |

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

Net effect: a container may reach the public internet, the containers its own
environment wires it to, and **one address on one port** on the private network —
its own environment's Postgres. Cloud metadata (`169.254.169.254`), the rest of
RFC1918 (including the database host's *other* ports and the other environment's
network entirely), CGNAT, loopback, this host, and everything else behind the
shared Caddy are unreachable.

Three properties worth knowing before you change anything:

- **The DROPs match the bridge interface (`br-+`), not a source subnet.** A
  network that is renamed, renumbered or created behind our back is filtered too
  — unknown traffic fails closed instead of falling through to the internet.
  Only the narrow RETURN exemptions are subnet-scoped, and those subnets are
  pinned by `infra/scripts/deploy.sh` (which derives `APP_SUBNET` from the
  environment name and passes it to `infra/compose.yml`) and by
  `docker network create` for `caddy-net` (§4). Keep the
  script's `MESH_SUBNETS` / `INGRESS_SUBNETS` / `DB_TARGETS` defaults in sync
  with them.
- **`DB_TARGETS` is the one hole in the RFC1918 block, and it is scoped on both
  ends** (`<container subnet>=<db ip>:5432`). Its addresses come from `servers`
  in `infra/terraform/platform/variables.tf` — renumber there and you must edit
  the script. It carries both environments' entries so the file is byte-identical
  on both app hosts; a rule naming a subnet that does not exist on this box
  matches nothing.
- **`caddy-net` is shared by the stack's own containers, so only Caddy may open
  connections on it** (`10.31.30.2`, pinned in `compose.caddy.yml`). Upstreams
  answer Caddy and never initiate. Note what this is worth: the exemption keys on
  a *source address* on a shared L2 segment, so a container with `CAP_NET_RAW`
  could forge it. It raises the cost of lateral movement; it is not a trust
  boundary you may lean on.

Install it **root-owned and outside `/opt/cookiekeeper`**, which CI rsyncs into: the
deploy key must not be able to rewrite the thing that constrains it.

```bash
# from a repo checkout on the server (or scp the three files up)
install -o root -g root -m 0755 infra/scripts/egress-firewall.sh \
  /usr/local/sbin/cookiekeeper-egress-firewall
install -o root -g root -m 0644 infra/scripts/cookiekeeper-egress-firewall.service \
  infra/scripts/cookiekeeper-egress-firewall.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now cookiekeeper-egress-firewall.service cookiekeeper-egress-firewall.timer
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
must work. Every negative probe targets something that genuinely answers with the
firewall removed (host sshd, this box's TLS port, **port 22 on the database
host**), so it cannot pass vacuously; the positive probes — public internet, DNS,
and 5432 on this environment's database — catch an over-blocking rule set, which
breaks every crawl and every request and is just as bad:

```bash
/usr/local/sbin/cookiekeeper-egress-firewall verify
```

Note what `verify` deliberately does **not** assert: that dev cannot reach prd's
database. On a two-machine-per-environment layout there is no route between the
private networks, so that probe would come back "closed" even with every rule
removed — it would prove nothing. The isolation there is physical.

Host-level prerequisites the rules depend on (cloud-init sets all of these; check
them if you rebuild a host by hand):

- **DNS must not be private.** Containers resolve via docker's embedded server,
  which forwards from inside the container — a private upstream would be dropped
  by these rules. Use the EU resolvers (also the right answer for data residency)
  in `/etc/docker/daemon.json`, then `systemctl restart docker`:
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

## 4. Directory layout, `caddy-net`, and the `.env`

Cloud-init already created `/opt/cookiekeeper/<env>`, `/opt/cookiekeeper/caddy`,
`/srv/cdn/<env>` and the `caddy-net` network.

**Check `caddy-net` kept its `--ip-range`.** `--ip-range` matters as much as
`--subnet`: docker's dynamic pool otherwise starts at `.2` — the address
`compose.caddy.yml` pins for Caddy and the one the egress rules exempt. Whichever
container comes up first would take it, and then Caddy fails to start (`Address
already in use`, exit 125) *or*, worse, some other container holds the one
address allowed to initiate connections on this network. The setting cannot be
changed in place, so a network created without it must be recreated:

```bash
docker network inspect caddy-net --format '{{ (index .IPAM.Config 0).IPRange }}'   # 10.31.30.128/25
# if empty: stop everything attached (docker compose down in /opt/cookiekeeper/{<env>,caddy}), then
docker network rm caddy-net
docker network create --subnet 10.31.30.0/24 --ip-range 10.31.30.128/25 caddy-net
```

**Write the environment file.** `/opt/cookiekeeper/<env>/.env` is created by hand,
`chmod 600`, and is never touched by automation — `deploy.sh` writes only
`.env.deploy` (the image tag), so nothing in CI can clobber a credential. Copy
the repo's `.env.example` and fill in real values; the split-specific ones are:

| Variable | dev | prd |
|----------|-----|-----|
| `DB_URL` | `jdbc:postgresql://10.20.10.20:5432/cookiekeeper?sslmode=verify-ca&sslrootcert=/etc/ssl/pgca.crt` | same with `10.20.20.20` |
| `DB_PASSWORD` | the password from §2.1 | its own, different password |
| `CADDY_ENV` | `dev` | `prd` |
| `MAIL_PROVIDER` | `smtp` (→ the Mailpit container) | `brevo` |
| `SMTP_HOST` / `SMTP_PORT` | `mailpit` / `1025` | unset |
| `BREVO_API_KEY` | unset | the live key |

Dev **must not** be able to send real mail: a test signup mailing a real person
is a constraint #4 violation, and dev traffic would burn Brevo's sending
reputation. Mailpit's UI is reachable only from the host (`ssh -L`), never
published.

`pgca.crt` sits alongside the `.env` and is bind-mounted into the api and scanner
containers at `/etc/ssl/pgca.crt` — that is why the JDBC URL names the container
path, not the host one.

Then bring up Caddy (its own compose project, one per app host — `CADDY_ENV`
selects `Caddyfile.dev` or `Caddyfile.prd`, both of which `import snippets.caddy`
from the same directory):

```bash
cd /opt/cookiekeeper/caddy   # holds compose.caddy.yml, Caddyfile.{dev,prd}, snippets.caddy, .env with CADDY_ENV
docker compose -f compose.caddy.yml --project-name cookiekeeper-caddy up -d
```

## 5. Deploy key (GitHub Actions → app hosts)

The `deploy` user and its `authorized_keys` come from cloud-init
(`ci_deploy_public_key`), on the **app hosts only** — CI has no reason to log
into a database host, and the narrower the deploy key's reach, the less a leaked
one is worth. Each environment gets its own key pair and its own set of secrets,
so a leaked dev key cannot touch prd.

GitHub repo → Settings → Secrets and variables → Actions:

| Secret | Value |
|--------|-------|
| `DEV_SSH_HOST` | `cookiekeeper-dev-app` public IP |
| `DEV_SSH_USER` | `deploy` |
| `DEV_SSH_KEY`  | the dev ed25519 **private** key |
| `PRD_SSH_HOST` | `cookiekeeper-prd-app` public IP |
| `PRD_SSH_USER` | `deploy` |
| `PRD_SSH_KEY`  | the prd ed25519 **private** key |

`deploy.sh` refuses to run if `hostname -s` is not `cookiekeeper-<env>-app`, so a
swapped `DEV_SSH_HOST`/`PRD_SSH_HOST` fails loudly instead of quietly deploying
the production tag onto dev.

Copy `infra/scripts/deploy.sh` and `uptime-check.sh` to `/opt/cookiekeeper/` and
`chmod +x` them (the deploy workflow also rsyncs `deploy.sh` on every deploy).
`backup.sh` and `restore-drill.sh` belong on the **database** hosts, not here.
`egress-firewall.sh` is deliberately not among them either — it lives root-owned
in `/usr/local/sbin` (§3), out of reach of anything the deploy key can write.

## 6. DNS (Cloudflare)

`infra/terraform/environments/` manages these per workspace and the release
pipelines apply it, pointing each environment's records at
`app_ipv4["<env>"]` from the platform state. Proxied, SSL mode "Full (strict)":

| Environment | Records | Target |
|-------------|---------|--------|
| dev | `dev`, `api.dev`, `cdn.dev` | `cookiekeeper-dev-app` |
| prd | apex, `app`, `api`, `cdn` | `cookiekeeper-prd-app` |

Database hosts get **no record**. Nothing should be able to find them by name,
and nothing needs to.

---

# 7. Backups (encrypted, off-site, drilled)

**These run on the database hosts**, one environment per host. That placement is
the point of the split: `pg_dump` goes over the local unix socket, so the
plaintext dump never crosses the network at all — and the app host, which is the
box running a web crawler, never holds a credential that can read every table.

Dumps hold visitor PII + append-only consent evidence, so they are **encrypted
client-side** before they ever leave the host (ARCHITECTURE.md §8, CLAUDE.md
#3/#4). `backup.sh` pipes `pg_dump | gzip | age` (plaintext never hits disk),
writes a `.sha256` sidecar, ships to Hetzner Object Storage (EU), and rotates.

### 7.1 Install tooling

Cloud-init installs `age` and `rclone` on the database hosts. Verify:

```bash
command -v age rclone || apt-get update && apt-get install -y age rclone
install -o root -g root -m 0700 infra/scripts/backup.sh infra/scripts/restore-drill.sh /opt/cookiekeeper/
```

### 7.2 Generate the backup keypair (write-only model)

Backups are encrypted to a **public** key, so a compromised host can write but
never read them. Generate the keypair **on your workstation, not the server**:

```bash
age-keygen -o cookiekeeper-backup-identity.txt      # KEEP OFFLINE (password manager)
# The file prints "Public key: age1..." — that public key goes on the server.
```

**Prove the pair round-trips before trusting it.** With the write-only model a
typo'd/wrong public key still encrypts cleanly, silently producing backups that
can *never* be decrypted. Verify before putting the key into service:

```bash
PUB="$(grep -oE 'age1[0-9a-z]+' cookiekeeper-backup-identity.txt | head -1)"
echo "roundtrip-ok" | age -r "$PUB" | age -d -i cookiekeeper-backup-identity.txt
# must print exactly: roundtrip-ok
```

- Put **only** the `age1...` public key on each database host (in `backup.env`).
- Store `cookiekeeper-backup-identity.txt` (the private key) in your password
  manager. It is required **only** for a restore. Never commit it, never leave
  it on a server. Consider generating a second identity as a break-glass
  recipient and listing both public keys (space-separated) in `BACKUP_AGE_RECIPIENT`.

One keypair for both environments is fine — the environments are separated by the
bucket prefix and by the machines, not by the encryption key.

### 7.3 Configure the off-site remote (EU region)

```bash
rclone config    # new remote "hetzner-s3", type=s3, provider=Other,
                 # endpoint=https://<eu-region>.your-objectstorage.com  (EU!)
                 # then create a bucket, e.g. cookiekeeper-backups
```

Set a bucket **lifecycle rule** to expire objects after ~90 days as a
belt-and-suspenders backstop to the script's own off-site prune (the script may
not run if the host is the thing that failed).

Both database hosts may share one bucket: `backup.sh` derives a per-environment
prefix (`<remote>/dev`, `<remote>/prd`) from its own hostname and scopes both the
upload and the `rclone delete --min-age` prune to it. That is computed in the
script rather than read from config, precisely so dev's nightly cron can never
prune prd's only off-site copy.

### 7.4 Backup env + cron

On **each** database host. Keep keys/remote out of the crontab and out of git —
put them in a root-only env file the cron sources:

```bash
umask 077
cat > /opt/cookiekeeper/backup.env <<'EOF'
BACKUP_AGE_RECIPIENT=age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
BACKUP_RCLONE_REMOTE=hetzner-s3:cookiekeeper-backups
# Optional overrides: BACKUP_LOCAL_RETENTION_DAYS, BACKUP_OFFSITE_RETENTION_DAYS, BACKUP_ENV
EOF
chmod 600 /opt/cookiekeeper/backup.env

crontab -e -u root
# daily 03:15 — encrypted dump of THIS host's database, sha256, off-site to Hetzner (EU), rotate
15 3 * * * set -a; . /opt/cookiekeeper/backup.env; /opt/cookiekeeper/backup.sh >> /var/log/cookiekeeper-backup.log 2>&1
```

`BACKUP_ENV` is derived from `hostname -s` (`cookiekeeper-dev-db` → `dev`), so a
correctly-built host needs no extra config — and a host whose name does not match
**refuses to run** rather than filing prd's dump under dev's name. Set it
explicitly only if you rename a machine.

`backup.sh` also refuses to run without `BACKUP_AGE_RECIPIENT` (never writes an
unencrypted dump) and exits non-zero on any failure so the log / uptime monitor
catches a broken backup.

### 7.5 Restore drill (do this before launch, then quarterly)

`restore-drill.sh` fetches a dump (newest local, or `--from-offsite <name>`),
verifies its checksum, decrypts + restores it into a **throwaway scratch DB** on
the same Postgres, runs sanity queries, and drops it — the live database is never
touched. Run it on the database host that owns the data; `--env` defaults to that
host's own environment, and prd's drill belongs on prd's box (restoring prd's PII
onto the dev machine would put production data on the lower-trust host).

It needs the **private identity**, which is not on the server, so supply it
out-of-band and remove it afterwards:

```bash
# Create the target 0600 FIRST so scp can't briefly land it world-readable,
# then copy the private identity up just for the drill and remove it after.
ssh root@<db public ip> 'install -m600 /dev/null /dev/shm/id.txt'
scp cookiekeeper-backup-identity.txt root@<db public ip>:/dev/shm/id.txt
ssh root@<db public ip>
set -a; . /opt/cookiekeeper/backup.env; set +a
/opt/cookiekeeper/restore-drill.sh --identity /dev/shm/id.txt
# or from off-site (proves the OFF-SITE copy — do this one before launch):
#   /opt/cookiekeeper/restore-drill.sh --identity /dev/shm/id.txt --from-offsite cookiekeeper-prd-<ts>.sql.gz.age
rm -f /dev/shm/id.txt   # `shred` is ineffective on tmpfs (RAM-backed); rm is what removes it
```

`/dev/shm` is RAM-backed, so ensure the host has **swap disabled or encrypted** —
otherwise the identity could be paged to disk. The `--from-offsite` drill is a
**blocking** launch-checklist gate: it's the only thing that proves the recipient
key is decryptable AND the off-site copy is intact.

**Real recovery** uses the same guarded pipeline — verify the checksum, restore
into a **fresh** DB with `ON_ERROR_STOP`, then repoint the app (never stream an
unverified dump straight into the live `cookiekeeper` DB — a corrupt/truncated
dump would half-apply):

```bash
cd /opt/cookiekeeper/backups
sha256sum -c cookiekeeper-prd-<ts>.sql.gz.age.sha256          # abort if this fails
runuser -u postgres -- psql -d postgres -c 'CREATE DATABASE cookiekeeper_restored OWNER cookiekeeper;'
age -d -i /dev/shm/id.txt cookiekeeper-prd-<ts>.sql.gz.age \
  | gunzip \
  | runuser -u postgres -- psql -d cookiekeeper_restored -v ON_ERROR_STOP=1
# Verify cookiekeeper_restored, then swap: rename it to `cookiekeeper` (the app host's
# DB_URL names the database, so no .env change is needed) and restart the app stack.
```

# 8. Sanity checklist

Per **database** host:

- [ ] `cookiekeeper` role has a password set by hand and it is in your password manager (§2.1)
- [ ] `SHOW listen_addresses` → `localhost,10.20.x.20` (never `*` or `0.0.0.0`)
- [ ] `pg_hba.conf` contains exactly one network line, `hostssl`, one `/32` source (§2.3)
- [ ] `nmap -Pn -p 5432 <db public ip>` from outside → closed/filtered
- [ ] Backup cron produced an **encrypted** dump (`*.sql.gz.age` + `.sha256`) under this environment's off-site prefix, and `restore-drill.sh --from-offsite` PASSED

Per **application** host:

- [ ] `psql "…sslmode=verify-ca&sslrootcert=…/pgca.crt" -c 'SELECT 1'` succeeds against its own database (§2.2) and the app starts
- [ ] Container egress firewall installed and **`cookiekeeper-egress-firewall verify` PASSED** (ADR-18 blocking gate — metadata / host sshd / this box's public IP / the database host's port 22 all closed; the database's 5432, public internet and DNS still open)
- [ ] `daemon.json` has the EU resolvers **and** `"userland-proxy": false`; `net.bridge.bridge-nf-call-iptables=1` and `net.netfilter.nf_conntrack_helper=0` in `/etc/sysctl.d/`; IPv6 off on all docker networks (§3 prerequisites)
- [ ] `caddy-net` has `--ip-range 10.31.30.128/25` so Caddy keeps `.2` (`docker network inspect caddy-net` → `IPRange` set; §4)
- [ ] `.env` is `chmod 600`, has `CADDY_ENV`, and dev's `MAIL_PROVIDER=smtp` (no `BREVO_API_KEY` on dev — nothing from dev may reach a real inbox)
- [ ] `deploy.sh` host guard passes (`hostname -s` == `cookiekeeper-<env>-app`)

End to end:

- [ ] `curl -I https://api.dev.cookiekeeper.eu/actuator/health` → 200
- [ ] `curl -I https://dev.cookiekeeper.eu` → 200
- [ ] `curl -I https://cdn.dev.cookiekeeper.eu/v1.js` → 200 with `max-age=3600` (NOT immutable — deploys overwrite v1.js in place)
- [ ] Load smoke test (`infra/load/README.md`) run against dev — reads p95 < 500ms, no 5xx; Tomcat/Hikari defaults confirmed against the dedicated database
- [ ] Uptime monitoring live (`infra/monitoring/uptime.md`): external checks on api health + dashboard + widget CDN, and `uptime-check.sh` heartbeat cron pinging green
