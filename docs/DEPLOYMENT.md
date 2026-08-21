# CookieKeeper — Deployment & Operations Guide

Complete setup instructions for all third-party services, environment configurations, and infrastructure.

---

## 0. Start here — your first deploy, in order

The rest of this document is organised by **topic** (Terraform, Cloudflare, Stripe, Compose…), which is what you want when you need to change one thing. It is the wrong shape when you have never deployed at all and need to know what to do first. This section is that path.

Read §1 first for the shape of the system — it is short. Then work down this list. **Do not skip ahead**: almost every step depends on the one above it, and the deploy script refuses to run when a prerequisite is missing, by design (§10.4).

Expect this to take the better part of a day the first time, most of it waiting for DNS and TLS.

### Stage A — accounts and state (nothing exists yet)

| # | Do this | Section | You are done when |
|---|---------|---------|-------------------|
| 1 | Create the Hetzner, Cloudflare, Stripe, Brevo, Sentry and GitHub accounts | §2 | All six exist; Brevo and Sentry are on their **EU** regions (you cannot change this later) |
| 2 | Register the domain and point it at Cloudflare's nameservers | §4.1 | Cloudflare shows the zone as **Active** — this can take hours, so start it early |
| 3 | Create the Hetzner Object Storage bucket for Terraform state | §3.3 | `terraform init` succeeds in both root modules |

### Stage B — machines (Terraform builds four servers)

| # | Do this | Section | You are done when |
|---|---------|---------|-------------------|
| 4 | `terraform apply` the **`platform/`** module **by hand** | §3.4 | `terraform output servers` lists four machines, all `running` |
| 5 | Harden all four hosts (SSH key-only, fail2ban, unattended-upgrades) | `infra/scripts/server-setup.md` | You can SSH with a key and not with a password |
| 6 | On each **app** host: install Docker, create `caddy-net`, bring up the Caddy stack | §5.3, `server-setup.md` §4 | `docker ps` shows `cookiekeeper-caddy` |
| 7 | On each **app** host: install the container egress firewall and run `egress-firewall.sh verify` | ADR-18, `server-setup.md` §3 | `verify` passes. **`deploy.sh` treats a missing firewall as fatal** — skip this and step 14 stops dead |

### Stage C — database (the one hand step that cannot be automated)

| # | Do this | Section | You are done when |
|---|---------|---------|-------------------|
| 8 | On each **db** host: set the `cookiekeeper` role password by hand | §11.1 | `psql` authenticates from the matching app host over the private IP |
| 9 | Generate the Postgres TLS cert; copy `server.crt` to the app host as `pgca.crt` | §11.1 | `pgca.crt` sits next to the `.env` and is readable by the container user |

> Why by hand: cloud-init creates the role **without** a password, so the host fails closed until an operator acts. A password passed through Terraform would be written to state in plaintext (§1.1).

### Stage D — configuration (the file automation can never write)

| # | Do this | Section | You are done when |
|---|---------|---------|-------------------|
| 10 | Write `/opt/cookiekeeper/dev/.env` and `/opt/cookiekeeper/prd/.env` by hand, `chmod 600` | §9, §13.5 | Every variable in `.env.example` is accounted for in both |
| 11 | Double-check `MAIL_PROVIDER`: **`smtp` on dev, `brevo` on prd** | §7.4 | Getting this backwards on dev mails real people. `deploy.sh` now refuses to deploy either mistake |
| 12 | Set the GitHub Actions secrets, and create the `production` environment with a required reviewer | §8.5 | `DEV_SSH_HOST`/`PRD_SSH_HOST` are the **app** hosts — CI must never reach a database host |

> **Do not put `ENV_NAME`, `APP_SUBNET`, `COMPOSE_PROFILES` or any `*_DIGEST` in these `.env` files.** They are machine-owned: `deploy.sh` derives and exports them per run, and a stale copy in `.env` is one of the few ways to make a deploy go quietly wrong. `.env.example` says so too.

### Stage E — first deploy

| # | Do this | Section | You are done when |
|---|---------|---------|-------------------|
| 13 | `terraform apply` the **`environments/`** module for workspace `dev` (the pipeline does this, but the first run is worth watching) | §3.5 | The six hostnames resolve and are proxied |
| 14 | Dispatch **`release-dev`** from the Actions tab, on `main` | §8.2 | Tag pushed, images built, `smoke` green |
| 15 | Log in, create a site, run a scan, embed the widget, record a consent | §13 | The flow works end to end against dev |
| 16 | Only then dispatch **`release-prd`** on the `vX.Y.Z` tag that step 14 created, and approve the gate | §8.2 | `smoke` green against production hostnames |

**Your first deploy has no rollback target.** `deploy.sh` recovers by restoring the last deploy that passed its health check, and on a virgin host there isn't one. If step 14 fails it will say `no distinct last-known-good deploy to roll back to`, leave the stack up so you can read it, and exit non-zero. That is correct behaviour, not a bug — read `deploy-failure-<timestamp>.log` next to the `.env` on the box (§10.4). From the second successful deploy onward, rollback is automatic.

**If step 14 stops immediately**, it is almost always one of the Phase-1 refusals in §10.4 — a missing egress firewall (step 7), a wrong `MAIL_PROVIDER` (step 11), or a secret missing from `.env` (step 10). Each aborts with a named message before touching a container.

Once all sixteen are done, work through §13 as a final audit — it covers the things that are easy to leave half-finished (backups, restore drill, rate limits, monitoring).

---

## 1. Overview

CookieKeeper runs on **four Hetzner CX22 servers** in Germany: an application host and a dedicated Postgres host for each of the exactly **two** environments, `dev` and `prd` (ADR-24). All customer data, backups, and infrastructure stay within the EU.

```
Cloudflare (DNS, CDN, WAF, TLS edge)         ← managed by Terraform
    ↓
Hetzner, Falkenstein — 4 × CX22 (2vCPU/4GB)  ← managed by Terraform
│
├─ cookiekeeper-dev-app        private net 10.20.10.0/24, .10
│   ├─ Caddy (reverse proxy, auto-TLS, routing)   ← Compose + rsynced Caddyfile.dev
│   ├─ api       (Spring Boot, profiles=api,dev)
│   ├─ dashboard (Next.js)
│   ├─ scanner   (Spring Boot, profiles=scanner,dev)
│   └─ mailpit   (dev only — mail must never reach a real inbox)
├─ cookiekeeper-dev-db         private net 10.20.10.0/24, .20
│   └─ Postgres 16 on the metal + nightly encrypted backup
│
├─ cookiekeeper-prd-app        private net 10.20.20.0/24, .10
│   └─ Caddy + api + dashboard + scanner (profiles=…,prd; mail via Brevo)
└─ cookiekeeper-prd-db         private net 10.20.20.0/24, .20
    └─ Postgres 16 on the metal + nightly encrypted backup

Stripe (billing webhooks)
Brevo (transactional email, EU region)
GitHub (source, Actions CI/CD, GHCR image registry)
```

The two environments sit on **separate private networks**, so dev has no route to prd's database — the isolation is physical, not a firewall rule that can be misconfigured. Postgres runs bare on its own machine rather than as a container beside the app: it stops a runaway crawl or a leaking JVM from taking the database's page cache with it, and it means the box that runs a web crawler never holds a credential that can read every table. Backups run on the database host, over the local unix socket, so a plaintext dump never crosses a network. Database hosts have a public IP for SSH and off-site backup egress only — no service listens on it and they have no DNS record.

### 1.1 The three things that own configuration

Read this before touching anything — most operational mistakes are putting a value in the wrong one of these.

| Owner | Owns | Changed by |
|-------|------|-----------|
| **Terraform** (`infra/terraform/`) | The four servers, the private networks, the cloud firewalls, DNS records, Cloudflare cache/rate-limit rules | `terraform apply` — by hand for `platform/`, by the release pipelines for `environments/` |
| **Docker Compose** (`infra/compose.yml`) | Which containers run on an app host, on which network, with which healthchecks and resource limits | committed to the repo, copied to the app host by the deploy job |
| **`.env` on the app host** | Every secret and every per-environment value | **you, by hand, over SSH** — `chmod 600`, never in git, never in Terraform |

Secrets are deliberately *not* in Terraform: every Terraform variable is written to state in plaintext, so a secret passed as a variable becomes a secret sitting in an object-storage bucket. That is also why the `cookiekeeper` database role is created **without a password** by cloud-init and you set it by hand afterwards (§11.1) — a passwordless role cannot authenticate against the scram-only `pg_hba.conf`, so the host fails closed until an operator acts. And secrets are deliberately not in a secrets manager either — for a two-environment product that is more moving parts than the problem justifies. The mechanism is a file you edit over SSH, and the deploy scripts are built so nothing can ever overwrite it: `deploy.sh` writes only `.env.deploy`, which contains the image tag and nothing else.

### 1.2 What a workstation is

Not a third environment. `infra/compose.workstation.yml` runs the **`dev`** Spring profile against a repo-root `.env`, plus a Mailpit container to catch outgoing mail. It is the one place a Postgres *container* still exists: nobody is provisioning a second machine to run tests on a laptop. Two behavioural differences follow from that — `COOKIE_SECURE=false`, because a browser silently discards a `Secure` cookie delivered over plain `http://localhost` (login would appear to work and never stick), and a `DB_URL` pointing at the container with no TLS parameters.

There used to be a `local` profile and an `application-local.yml`. Both are gone: the value of a laptop run is that it exercises the same configuration path production does.

---

## 2. Prerequisites & Accounts

Create accounts **in this order** before provisioning infrastructure:

| Service | Purpose | Cost | Account Type |
|---------|---------|------|--------------|
| **GitHub** | Source repo, Actions CI, GHCR registry | Free | Personal or Org |
| **Hetzner** | 4 × CX22 + Object Storage (backups **and** Terraform state) | €30.89/mo excl. VAT + storage | Dedicated account |
| **Cloudflare** | DNS, CDN, WAF, edge caching | Free | Dedicated account |
| **Stripe** | Billing, checkout, subscriptions, EU VAT | Per-transaction | Merchant account |
| **Brevo** | Transactional email (300/day free) | Free tier | **EU region** account |

Local tooling for the one-time bootstrap:

| Tool | Version | Why |
|------|---------|-----|
| Terraform | ≥ 1.10 (CI pins 1.15.8) | 1.10 introduced `use_lockfile`, the S3-native state locking we rely on |
| `hcloud` CLI | latest | handy for `hcloud server-type list`; not required |
| `age` | latest | backup encryption keypair |

> OpenTofu is a drop-in substitute — the configuration uses no HashiCorp-licensed features. Substitute `tofu` for `terraform` throughout if you prefer.

---

## 3. Terraform — infrastructure as code

### 3.1 Why two root modules

```
infra/terraform/
├── platform/       ← all four servers, both private networks, cloud firewalls,
│                     SSH keys, zone-wide Cloudflare settings
└── environments/   ← per-environment DNS, cache rules, rate-limit rules
```

`platform/` holds the resources whose accidental destruction would be unrecoverable: the servers, and above all the database volumes on them. It is applied **by hand, never by a pipeline**, and every server carries `prevent_destroy = true`.

`environments/` is applied *by* the release pipelines, with per-environment state isolation through a Terraform **workspace** named `dev` or `prd`. The split means a routine dev deploy's plan physically cannot contain "destroy the production database server" — that resource isn't in its state.

There is a guard against the classic workspace mistake: applying `prd.tfvars` while the `dev` workspace is selected. `environments/main.tf` has a precondition that fails the plan with the exact `terraform workspace select` command to run.

### 3.2 What Terraform deliberately does NOT own

- **Secrets.** See §1.1. Terraform variables land in state in plaintext — including the `cookiekeeper` database password, which is why cloud-init never sets one.
- **Containers.** Docker Compose owns those; Terraform stops at the server.
- **The Caddyfile.** It is rsynced by the deploy job, so a routing change doesn't need a `terraform apply`.
- **The state bucket.** Chicken-and-egg — created once by hand in §3.3.
- **DNS for the marketing site**, if you host it elsewhere. `marketing_host` is nullable for exactly this reason; `dev.tfvars` sets it to `null`.

### 3.3 Bootstrap the state backend (once, by hand)

State lives in Hetzner Object Storage rather than Terraform Cloud, because Terraform Cloud is a **US-hosted processor** and CLAUDE.md constraint #2 forbids introducing one without an ADR.

1. Hetzner Console → **Object Storage** → create a bucket in **Falkenstein (fsn1)**:
   - `cookiekeeper-tfstate` — versioning **enabled** (state file corruption is recoverable only if you kept the old versions)
2. Create a second bucket `cookiekeeper-backups` in the same region (used by §11.2).
3. Generate **S3 credentials** (Object Storage → Manage credentials). Note the access key and secret; the secret is shown once.

The backend is already configured in `platform/versions.tf` and `environments/versions.tf`. It uses `use_lockfile = true` — Terraform ≥ 1.10's native S3 conditional-write locking, so there is no DynamoDB table to run.

### 3.4 Apply the platform module

```bash
cd infra/terraform/platform
cp terraform.tfvars.example terraform.tfvars   # gitignored
$EDITOR terraform.tfvars
```

Fill in:

| Variable | What | Notes |
|----------|------|-------|
| `ssh_public_key` | your admin key | the one you log in with |
| `ci_deploy_public_key` | the deploy key's public half | generated in §8.4 — a *separate* key from yours, and installed on the **app hosts only** |
| `admin_ssh_cidrs` | who may reach port 22 | your home/office IP. `0.0.0.0/0` works but is worth avoiding |
| `cloudflare_zone_id` | from the Cloudflare dashboard overview page | |
| `location` | `fsn1` or `nbg1` | validated — any other value fails the plan, because EU residency is not negotiable |
| `servers` | the four machines, their roles, types and **static** private IPs | defaulted; only edit to resize or renumber (see below) |

`servers` is a map keyed by hostname, with validations that refuse anything but exactly one `app` and one `db` per environment. The private IPs are assigned statically rather than by DHCP because three things read them and none can wait for a computed value: the db host's `pg_hba.conf` and ufw rules (rendered into cloud-init at create time), the app host's JDBC URL (hand-written into `.env`), and the container egress firewall's narrow 5432 exemption. **Renumbering means editing `infra/scripts/egress-firewall.sh`'s `DB_TARGETS` default too** — it is the one place outside Terraform that hard-codes an address.

Then:

```bash
export HCLOUD_TOKEN="…"                    # Hetzner Cloud API token (read+write)
export CLOUDFLARE_API_TOKEN="…"            # scoped token, see §4.1
export AWS_ACCESS_KEY_ID="…"               # the Object Storage credentials from §3.3
export AWS_SECRET_ACCESS_KEY="…"

terraform init
terraform plan      # read it
terraform apply
terraform output servers            # all four: public IP, private IP, role
terraform output -json app_ipv4     # {"dev": "…", "prd": "…"} — the DNS targets
```

Outputs are **maps keyed by environment**, not single values, because there is no longer "the" server.

Two cloud-init files run on **first boot only**, one per role:

- `cloud-init-app.yaml` — Docker CE, the `deploy` user, the docker daemon config (EU resolvers, `userland-proxy: false`, IPv6 off), the ADR-18 sysctls, sshd hardening, ufw (22/80/443), `caddy-net`, and the `/opt/cookiekeeper/{<env>,caddy}` + `/srv/cdn/<env>` tree.
- `cloud-init-db.yaml` — Postgres 16 on the metal, tuned for a machine that runs nothing else, with `listen_addresses` bound to the private IP, a replaced `pg_hba.conf` whose only network line is `hostssl … <app private ip>/32 scram-sha-256`, a self-signed TLS keypair with an IP SAN, ufw permitting 5432 from one address, and `age` + `rclone` for backups. It creates the role and database **without a password**.

`user_data` is in `ignore_changes` precisely because editing it after the fact would otherwise show a plan that replaces the server — and on a database host that plan destroys the data. To change first-boot behaviour on a live box, change the box, then update the file so a future rebuild matches.

The cloud firewalls open 22 (from `admin_ssh_cidrs` only) and ICMP everywhere, plus 80/443 on the app hosts. Port 5432 appears in no cloud firewall at all — and that is worth understanding rather than trusting: **Hetzner cloud firewalls filter public traffic only and cannot see private-network traffic**, so they are structurally incapable of protecting 5432. The controls that actually do it are all on the database host (`listen_addresses`, ufw, `pg_hba.conf`), which is why §11.1 has you verify each one.

### 3.5 Apply an environment module

Normally the pipelines do this. By hand:

```bash
cd infra/terraform/environments
export CLOUDFLARE_API_TOKEN="…" AWS_ACCESS_KEY_ID="…" AWS_SECRET_ACCESS_KEY="…"
export TF_VAR_cloudflare_zone_id="…"
export TF_VAR_origin_ipv4="$(cd ../platform && terraform output -json app_ipv4 | jq -r .dev)"

terraform init
terraform workspace select dev || terraform workspace new dev
terraform plan -var-file=dev.tfvars
terraform apply -var-file=dev.tfvars
```

Swap `dev` → `prd`, `dev.tfvars` → `prd.tfvars`, and `.dev` → `.prd` in the `origin_ipv4` lookup for production. **All parts must change together** — the workspace guard will stop you if the workspace and the tfvars disagree, though nothing but care stops you pointing dev's records at prd's IP, so read the plan.

`origin_ipv4` is always an **app** host. Database hosts get no DNS record at all.

This module creates:

- **DNS records** for every host in the environment, proxied through Cloudflare (orange cloud) with `ttl = 1` (automatic — the only TTL Cloudflare honours for a proxied record).
- **Cache rules**: `/cfg/*` at `widget_config_ttl_seconds` (30s in dev so you can iterate, 300s in prd), `/p/*` at `policy_page_ttl_seconds` (1h), and an explicit **bypass** on `POST /api/v1/consent` and `/api/v1/impression`. That bypass is not an optimisation — CLAUDE.md constraint #3 makes consent events append-only audit evidence, and a cached write path would silently drop them.
- **A rate-limit rule** on `POST /api/v1/consent`, per IP, at `consent_rate_limit_per_minute`.

### 3.6 Terraform in CI

`quality.yml` runs `terraform fmt -check -recursive -diff` and `terraform init -backend=false && terraform validate` for both modules on every branch. `-backend=false` means validation needs no credentials at all, so it is safe on pull requests.

`release-dev` and `release-prd` run a real `apply` against `environments/` only. They read the origin IP out of `platform/`'s state read-only; they never plan against it.

---

## 4. Cloudflare

### 4.1 Domain, nameservers and the API token

1. **Add the domain to Cloudflare:** https://dash.cloudflare.com/
2. **Update the registrar** to Cloudflare's nameservers (shown during onboarding).
3. **Wait for propagation** (15–30 minutes) and confirm the zone is Active.
4. **Copy the Zone ID** from the zone's Overview page (right-hand column) — this is `cloudflare_zone_id`.
5. **Create an API token** (My Profile → API Tokens → Create Token → Custom):

| Permission | Scope | Why |
|------------|-------|-----|
| Zone → DNS → Edit | this zone | create the six host records |
| Zone → Zone Settings → Edit | this zone | TLS mode, HSTS, min TLS version |
| Zone → Cache Rules → Edit | this zone | the `/cfg/` and `/p/` rules |
| Zone → Config Rules / Rulesets → Edit | this zone | the rate-limit ruleset |

Restrict it to the one zone. This token goes into the `CLOUDFLARE_API_TOKEN` GitHub secret (§8.4) and your shell for a manual apply.

### 4.2 DNS records — created by Terraform, not by hand

Do **not** click these into the dashboard. `environments/main.tf` creates them from the `*_host` variables in the tfvars, so the record set and the application's `APP_BASE_URL`/`API_BASE_URL`/`CDN_BASE_URL` come from one place:

```
dev  (workspace dev,  dev.tfvars):
  dev.cookiekeeper.eu          A → origin, proxied
  api.dev.cookiekeeper.eu      A → origin, proxied
  cdn.dev.cookiekeeper.eu      A → origin, proxied

prd  (workspace prd,  prd.tfvars):
  app.cookiekeeper.eu          A → origin, proxied
  api.cookiekeeper.eu          A → origin, proxied
  cdn.cookiekeeper.eu          A → origin, proxied
  cookiekeeper.eu              A → origin, proxied   (marketing_host)
```

If you add a hostname, add it to the tfvars and re-apply — a hand-created record will be untracked, and the next apply will not know it exists.

### 4.3 Zone settings — also Terraform

`platform/main.tf` sets these once for the whole zone, since they are not per-environment:

| Setting | Value | Why |
|---------|-------|-----|
| SSL mode | **Full (Strict)** | edge validates the origin cert; anything less lets a MITM sit between Cloudflare and the origin |
| Always Use HTTPS | on | |
| Minimum TLS version | 1.2 | |
| HSTS | on, 1 year, includeSubdomains, `preload = false` | preload is a one-way door — turn it on manually once you are sure |
| Brotli | on | |
| Cache level | standard | |

No origin certificate is needed. Caddy obtains a publicly trusted Let's Encrypt certificate over ACME and renews it itself, which satisfies Full (strict) — see §12.5 for why we chose that over a Cloudflare origin cert.

---

## 5. Caddy Reverse Proxy Setup

### 5.1 What Caddy is here for

Caddy is the only thing on an app host listening on ports 80 and 443. It terminates TLS, obtains and renews Let's Encrypt certificates by itself, routes every request to the right container by hostname, serves the widget bundle from disk, and applies the security-header and request-size baseline before any application sees a byte.

There is **one Caddy per app host** — so one per environment, since ADR-24 gave each environment its own machine. It is still deliberately *not* a service inside the application stack: deploying the app must never interrupt TLS, and a separate compose project means the certificate store survives a `compose down` of the stack next to it.

The routing table and cache policy are explained in §12; this section is about getting it running.

### 5.2 The files

All live in the repo — do not hand-write a Caddyfile on the server:

| Repo path | On the app host |
|-----------|-----------------|
| `infra/caddy/Caddyfile.dev` *or* `Caddyfile.prd` | `/opt/cookiekeeper/caddy/Caddyfile.<env>` |
| `infra/caddy/snippets.caddy` | `/opt/cookiekeeper/caddy/snippets.caddy` |
| `infra/caddy/compose.caddy.yml` | `/opt/cookiekeeper/caddy/compose.caddy.yml` |

There is one Caddyfile per environment because each host serves only its own hostnames — a dev box that still had prd's site blocks would try to obtain a Let's Encrypt certificate for `api.cookiekeeper.eu` on a machine that address does not resolve to, and fail every renewal forever.

`snippets.caddy` holds the parts that must not drift between the two: the security-header baseline, the consent request-body cap, and the CDN vhost definition. Both Caddyfiles `import snippets.caddy` — Caddy's `import` is textual and resolves relative to the importing file's directory, so the two mounted files behave as one.

`compose.caddy.yml` picks which one to mount from `CADDY_ENV` in `/opt/cookiekeeper/caddy/.env`, and the variable has no default (`${CADDY_ENV:?…}`) — an unset value stops the container from starting rather than silently serving the wrong environment's vhosts.

`cookiekeeper.eu` is a placeholder throughout. Replace the hostnames when the real domain is bought, and keep them in sync with `infra/terraform/environments/{dev,prd}.tfvars` (which creates the DNS records) and the `*_BASE_URL` values in each `.env`.

### 5.3 One-time setup

Cloud-init creates `caddy-net` and the directory tree. Confirm the network kept its address range, because it is not arbitrary:

```bash
docker network inspect caddy-net --format '{{ (index .IPAM.Config 0).IPRange }}'   # 10.31.30.128/25
```

The `--ip-range` reserves the bottom of the subnet so Caddy keeps `10.31.30.2`, which `compose.caddy.yml` pins. That pin is load-bearing for the egress firewall (ADR-18): only that one address is allowed to *open* connections inside `caddy-net`. Application containers answer Caddy on established connections and can never initiate one. Recreate the network (`docker network rm` then `create`) if the range is missing — it cannot be changed in place.

Then start it:

```bash
# copy infra/caddy/{Caddyfile.<env>,snippets.caddy,compose.caddy.yml} into /opt/cookiekeeper/caddy/
cd /opt/cookiekeeper/caddy
printf 'CADDY_ENV=dev\n' > .env && chmod 600 .env      # or prd, on the prd host
docker compose -f compose.caddy.yml --project-name cookiekeeper-caddy up -d
```

Certificates are issued on first request per hostname, so DNS must already resolve to this host. `/srv/cdn` is mounted read-only into the container; CI writes into it from outside.

### 5.4 Changing the config

Caddy is not part of a release pipeline — it changes rarely and by hand. Validate before reloading, and reload rather than restart so no connection is dropped:

```bash
cd /opt/cookiekeeper/caddy
docker compose -f compose.caddy.yml --project-name cookiekeeper-caddy exec caddy \
  caddy validate --config /etc/caddy/Caddyfile
docker compose -f compose.caddy.yml --project-name cookiekeeper-caddy exec caddy \
  caddy reload --config /etc/caddy/Caddyfile
```

A change made only on the server is a change that disappears the next time someone copies the repo version over it. Edit `infra/caddy/Caddyfile.<env>` (or `snippets.caddy`), commit, then copy — **to both hosts** when the change is in the shared snippets file.

---

## 6. Stripe Setup (Billing)

### 6.1 Create Merchant Account

1. Go to **https://dashboard.stripe.com**
2. **Activate account** with business details
3. **Enable EU VAT collection** via Stripe Tax (€0.50/tx accepted)
4. **Set up bank account** for payouts (SEPA, EU bank preferred)

### 6.2 API keys — one account, two modes

You need **one** Stripe account, not two. Stripe gives every account a *test mode* and a *live mode* with completely separate keys, customers, products and webhooks. dev uses test mode; prd uses live mode.

**Dashboard → Developers → API keys** (toggle test/live with the switch at the top right)

Copy the **secret key** only — `sk_test_…` for the dev `.env`, `sk_live_…` for the prd `.env`. The publishable key is not used: the dashboard never talks to Stripe directly; the backend creates Checkout Sessions and Billing Portal sessions and redirects the browser to a Stripe-hosted URL. That is deliberate — no card data ever reaches our origin.

The two modes are not interchangeable. A test-mode price id against a live key fails, and vice versa, so never copy a value between the two `.env` files.

### 6.3 Products and prices

Create three recurring Products (Starter, Pro, Business) with a monthly Price each, **in both modes** — test-mode ones for dev, live ones for prd. Copy each Price id (`price_…`) into `STRIPE_PRICE_STARTER` / `STRIPE_PRICE_PRO` / `STRIPE_PRICE_BUSINESS` in the corresponding `.env`. The backend fails fast at startup if any is unset, so a typo here is caught at boot, not at a customer's first checkout.

### 6.4 Webhook endpoints

**Dashboard → Developers → Webhooks → Add endpoint** — once in test mode, once in live mode.

| Mode | Endpoint URL |
|------|--------------|
| test | `https://api.dev.cookiekeeper.eu/api/v1/billing/webhook` |
| live | `https://api.cookiekeeper.eu/api/v1/billing/webhook` |

Events to send: `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`. Those are the only ones the handler acts on — a subscription's status *is* the entitlement state. Anything else you subscribe to is accepted, recorded and ignored, so over-subscribing is harmless but pointless.

Each endpoint has its **own signing secret** (`whsec_…`). Put the test one in the dev `.env` and the live one in the prd `.env`. They are not interchangeable, and the backend refuses to start with a blank one — an empty signing secret would HMAC with an empty key and silently accept forged webhooks.

### 6.5 Stripe Tax

**Settings → Tax**: set the business address, register the VAT obligations that apply to you, and enable automatic calculation. Checkout then computes VAT per customer location (EU rates run 17–27%).

If Tax is not configured yet, set `STRIPE_AUTOMATIC_TAX=false` in that environment's `.env`. Leaving it `true` against an unconfigured account makes Checkout fail rather than fall back — the flag exists so dev can run without duplicating the tax setup.

---

## 7. Brevo Email Setup (Transactional Email)

**Why Brevo?** EU-based (France), generous free tier (300 emails/day), simple HTTP API, no external dependency in code.

**Brevo is production-only.** Dev delivers to a Mailpit container instead (§7.6), so no email originating outside prd can reach a real person. Everything in this section applies to prd unless it says otherwise.

### 7.1 Create the account — EU region, and you only get one chance

1. Go to **https://www.brevo.com** and create the account with a business email.
2. **Select the EU data region during signup.** This is ADR-14 and constraint #2: the region is a property of the *account* and cannot be changed afterwards — you would have to create a new account and re-verify everything. The API base URL does **not** pin residency, so `BREVO_BASE_URL` being `https://api.brevo.com` proves nothing; the account setting is the control.
3. Verify the sending domain (not just the address): **Senders, Domains & Dedicated IPs → Domains**. Add `cookiekeeper.eu`, then add the DKIM and SPF records Brevo shows you to Cloudflare DNS, plus a DMARC record. Unverified domains land in spam and, for a compliance product, that is a product problem rather than a deliverability one.

### 7.2 API key

**SMTP & API → API keys → Generate a new API key**. The v3 key starts with `xkeysib-`.

You need **exactly one key, for prd**. Dev does not send mail through Brevo at all — it delivers to a Mailpit container instead (§7.6) — so there is no dev key to leak, and no dev traffic on your sending reputation.

The app talks to Brevo over the **HTTP API** (`BrevoEmailSender`), not SMTP. Brevo's SMTP relay exists, and `MAIL_PROVIDER=smtp` selects an SMTP sender, but that mode is only ever pointed at Mailpit — never at Brevo.

### 7.3 Senders

**Senders, Domains & Dedicated IPs → Senders** — add and verify:

- `no-reply@cookiekeeper.eu` — every transactional email (verification, password reset, scan reports, billing notices)
- `support@cookiekeeper.eu` — the support inbox; also the destination for the in-app contact form

Note the hyphen in `no-reply@`; it must match `MAIL_FROM` in `.env` exactly.

### 7.4 Environment variables

**prd** — the only environment that mails real people:

```bash
# /opt/cookiekeeper/prd/.env
MAIL_PROVIDER=brevo
MAIL_FROM=no-reply@cookiekeeper.eu
BREVO_SENDER_NAME=CookieKeeper
BREVO_API_KEY=xkeysib-...
BREVO_BASE_URL=https://api.brevo.com     # override only to point at a mock
SUPPORT_INBOX=support@cookiekeeper.eu
```

**dev** — everything is captured locally; no Brevo key, and none of the Brevo variables are read:

```bash
# /opt/cookiekeeper/dev/.env
MAIL_PROVIDER=smtp
SMTP_HOST=mailpit
SMTP_PORT=1025
MAIL_FROM=no-reply@cookiekeeper.eu
SUPPORT_INBOX=support@cookiekeeper.eu
```

The backend fails fast at startup when `MAIL_PROVIDER=brevo` and the key is missing, so a mail misconfiguration surfaces as a container that will not start rather than as email that silently never arrives.

### 7.5 Deliverability sanity check

After the first deploy, trigger a real signup verification email to an address on a different provider and check: it arrived, it is not in spam, `From` shows the display name, and the DKIM/SPF results pass in the raw headers. Brevo's **Statistics → Email** dashboard shows bounces and blocks; a spike there is usually a DNS record that was added to the wrong zone.

### 7.6 Mailpit — a workstation *and* the dev host

**Mailpit** accepts any SMTP mail and shows it in a web UI instead of delivering it. It runs in two places, for the same reason: nothing outside production may send email to a real person. A test signup with a colleague's address, a seeded fixture, a bug that mails the wrong recipient — on dev these all land in Mailpit and go no further. That is constraint #4 in practice, and it keeps dev traffic off Brevo's sending reputation and quota.

`infra/compose.workstation.yml` declares the service outright; in `infra/compose.yml` it sits behind the **`dev` compose profile**, which `deploy.sh` enables only when the environment is `dev`. Prd never starts it — which also means `MAIL_PROVIDER=smtp` in a prd `.env` would send mail nowhere at all.

**On a workstation** the defaults in `.env.example` (`MAIL_PROVIDER=smtp`, `SMTP_HOST=mailpit`, `SMTP_PORT=1025`) already point at it — no Brevo key needed. Read captured mail at http://localhost:8025.

**On the dev host** the same three variables go in `/opt/cookiekeeper/dev/.env`. The web UI is published on **loopback only** (`127.0.0.1:8025:8025`), never the public interface, because captured mail contains magic links, password-reset tokens and recipient addresses — an open Mailpit is an account-takeover endpoint. Reach it through an SSH tunnel:

```bash
ssh -L 8025:127.0.0.1:8025 <user>@<dev-host>
# then open http://localhost:8025 in your browser
```

Mailpit keeps a 500-message ring buffer (`MP_MAX_MESSAGES`), so old test mail is dropped rather than accumulating on disk. There is nothing to back up — if the container is lost, the only casualty is test mail.

---

## 8. GitHub — pipelines, registry, secrets

### 8.1 Repository

1. Create the repo on **https://github.com** (private is fine for the MVP).
2. Protect **main** (Settings → Branches → Add rule):
   - Require the `validate` status checks to pass.
   - Require a pull request before merging.
   - Do **not** require linear history if you want the release commit to land cleanly — `release-dev` pushes a version-bump commit directly to `main`.
3. Create a **`production` environment** (Settings → Environments → New environment → `production`) and add yourself as a **required reviewer**. This is what makes `release-prd` pause for approval before it touches production.

### 8.2 The four pipelines

There is no auto-deploy. Merging to `main` proves the code is releasable; deciding to release it is a separate, manual act.

| Workflow | Trigger | Tests | Builds | Deploys |
|----------|---------|:-----:|:------:|---------|
| `validate.yml` | PR, push to any non-`main` branch | ✗ | ✗ | — |
| `main.yml` | push to `main` | ✓ | ✓ | — |
| `release-dev.yml` | **manual**, on `main` | ✓ | ✓ | dev |
| `release-prd.yml` | **manual**, on a `vX.Y.Z` tag | ✓ | ✓ | prd |

All four call one reusable workflow, `quality.yml`, with different inputs — so "is this code OK?" has exactly one definition, and the difference between a feature branch and a release is which flags are on. `quality.yml` is paths-filtered: a dashboard-only PR does not spin up a Gradle job.

> **Tests are off on feature branches** (`run_tests: false` in `validate.yml`). That is your stated preference and it is a real trade-off: a PR can go green while its tests are red, and the failure only surfaces once it lands on `main`. Flipping that one flag to `true` re-enables them — it is a one-line change because the gates are shared.

**`release-dev` — cutting a release**

Actions tab → `release-dev` → Run workflow → branch `main` → pick `patch`/`minor`/`major`.

1. Full quality gate.
2. Read `VERSION`, increment, write it back, mirror into `dashboard/package.json` and `widget/package.json`.
3. Commit the bump, **then** tag it, and push both atomically.
   The order matters: tagging first would leave `v1.2.3` pointing at a tree whose `VERSION` still says `1.2.2` — the artifact and the tag would disagree about what they are, which is the one question a tag exists to answer.
4. Build the three images **from the tag** and push them as `:vX.Y.Z`.
5. `terraform apply` the `environments` module in workspace `dev`.
6. Build the widget (size gate included) → `/srv/cdn/dev/`; copy the compose file + `deploy.sh`; run the deploy.
7. Smoke: `/actuator/health`, dashboard, CDN.

The bump commit is pushed with the default `GITHUB_TOKEN`, which by design does **not** re-trigger workflows — so the release commit does not kick off `main.yml`, and the new tag does not auto-start `release-prd`. Production is always a deliberate second act.

**`release-prd` — promoting a release**

Actions tab → `release-prd` → Run workflow → **switch the ref selector to the tag** `vX.Y.Z`.

1. Guard: refuses to run on anything but a `vX.Y.Z` tag.
2. Full quality gate on the tagged tree.
3. Verify the `:vX.Y.Z` images already exist in GHCR. If they don't, it **fails rather than building them** — production must never be the first place an artifact runs.
4. Manual approval (the `production` environment).
5. `terraform apply` in workspace `prd`. The plan is printed before it is applied so the approval is against a visible diff.
6. Widget → `/srv/cdn/prd/`; deploy; Flyway migrates on container boot.
7. Smoke.

**Rollback** is dispatching `release-prd` on the previous tag. The images are still in GHCR. There is no automatic rollback on a failed smoke test — a half-migrated database is usually worse than a red check, so the decision stays human.

### 8.3 GHCR (image registry)

Free, GitHub-native, nothing extra to sign up for. The workflows authenticate with the automatic `GITHUB_TOKEN`; you only need a personal token for the app hosts to *pull* (database hosts run no containers and never log in to GHCR):

1. **Settings → Developer settings → Personal access tokens → Tokens (classic)** → scopes `read:packages`.
2. On each app host, as the `deploy` user:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u <YOUR_GITHUB_USERNAME> --password-stdin
```

Images:

- `ghcr.io/<owner>/cookiekeeper-api:vX.Y.Z`
- `ghcr.io/<owner>/cookiekeeper-dashboard:vX.Y.Z`
- `ghcr.io/<owner>/cookiekeeper-scanner:vX.Y.Z`

`main.yml` also pushes `:sha-<sha>` and `:main` tags. Those are for bisecting, not for deploying.

> If the repo is private, make the *packages* readable to the host's token — or keep the token scoped to the same account and it works out of the box.

### 8.4 Deploy key

A key used **only** by CI, separate from your admin key. Generate it on your machine (not the server — the private half needs to reach GitHub, not stay on the box):

```bash
ssh-keygen -t ed25519 -f ~/.ssh/cookiekeeper_deploy -N "" -C "cookiekeeper-ci"
```

The **public** half goes into `ci_deploy_public_key` in `platform/terraform.tfvars`, which installs it for the `deploy` user via cloud-init — on the **app hosts only**. CI has no reason to log into a database host, and the narrower the deploy key's reach, the less a leaked one is worth. The **private** half becomes an Actions secret.

Generate **two** key pairs, one per environment, and give each its own secrets (below). A single shared key would mean a leaked dev credential is also a production credential — which would give back most of what the machine split bought.

### 8.5 Actions secrets

**Settings → Secrets and variables → Actions.** Everything the pipelines need, and nothing more — note that no application secret appears here, because those live only in the `.env` on the app host.

The SSH secrets are **per environment**, so `release-dev` has no credential that reaches production:

| Secret | Used by | What |
|--------|---------|------|
| `DEV_SSH_HOST` | `release-dev` | `cookiekeeper-dev-app`'s IPv4 (`terraform output -json app_ipv4 \| jq -r .dev`) |
| `DEV_SSH_USER` | `release-dev` | `deploy` |
| `DEV_SSH_KEY` | `release-dev` | private half of the dev deploy key |
| `PRD_SSH_HOST` | `release-prd` | `cookiekeeper-prd-app`'s IPv4 (`… jq -r .prd`) |
| `PRD_SSH_USER` | `release-prd` | `deploy` |
| `PRD_SSH_KEY` | `release-prd` | private half of the prd deploy key |
| `CLOUDFLARE_API_TOKEN` | both release workflows | the scoped token from §4.1 |
| `CLOUDFLARE_ZONE_ID` | both release workflows | passed as `TF_VAR_cloudflare_zone_id` |
| `TFSTATE_ACCESS_KEY_ID` | both release workflows | Hetzner Object Storage key (§3.3) |
| `TFSTATE_SECRET_ACCESS_KEY` | both release workflows | its secret |

`GITHUB_TOKEN` is automatic — don't create one.

If you ever swap `DEV_SSH_HOST` and `PRD_SSH_HOST` by accident, `deploy.sh` catches it: it refuses to run unless `hostname -s` matches `cookiekeeper-<env>-app`. Without that guard the failure mode is silent — same command, same exit code, wrong machine.

---

## 9. Environment variables and secrets

### 9.1 Where secrets live

**In one place per environment: a `.env` file on that environment's app host that you edit by hand.**

| Location | Host | Contents | Permissions |
|----------|------|----------|-------------|
| `/opt/cookiekeeper/dev/.env` | `cookiekeeper-dev-app` | every value the dev stack needs | `600`, owned by `deploy` |
| `/opt/cookiekeeper/prd/.env` | `cookiekeeper-prd-app` | every value the prd stack needs | `600`, owned by `deploy` |
| `/opt/cookiekeeper/<env>/.env.deploy` | app host | `IMAGE_TAG=vX.Y.Z` and nothing else | written by `deploy.sh` |
| `/opt/cookiekeeper/caddy/.env` | app host | `CADDY_ENV=<env>` | `600` |
| `/opt/cookiekeeper/backup.env` | **database** host | age recipient + rclone remote | `600`, root-only |
| `./.env` (repo root, gitignored) | your machine | your workstation values | your machine only |

`deploy.sh` passes both app-host files to Compose (`--env-file .env --env-file .env.deploy`) and only ever *writes* the second one. That separation is the whole safety property: no automation has a code path that can overwrite a credential.

Not in Terraform (variables land in state in plaintext, and that state sits in a bucket). Not in GitHub Actions secrets (the pipeline has no reason to know your Stripe key). Not in a secrets manager — for two environments that is more machinery than the problem is worth.

The one credential that lives in two places is the `cookiekeeper` database password: `pg_authid` on the database host, and `DB_PASSWORD` in the app host's `.env`. Each environment has its own — reusing one would mean a dev compromise hands over prd's database.

**Rotating a secret** is: SSH in, edit the file, re-run `release-dev`/`release-prd` (or just `docker compose up -d` for that project). Rotating the *database* password is the one two-step: `ALTER ROLE cookiekeeper PASSWORD …` on the database host, then `DB_PASSWORD` on the app host, then restart the stack. Do it in that order — the reverse leaves the app authenticating with a password the server has not been told about yet.

### 9.2 The variable reference

**[`.env.example`](../.env.example) is the authority** — every variable is listed there with what it does and whether it is optional. Copy it and fill it in:

```bash
scp .env.example root@<DEV_APP_IP>:/opt/cookiekeeper/dev/.env
ssh root@<DEV_APP_IP> 'chmod 600 /opt/cookiekeeper/dev/.env && chown deploy:deploy /opt/cookiekeeper/dev/.env'
ssh -t root@<DEV_APP_IP> 'vi /opt/cookiekeeper/dev/.env'
```

(The `deploy` user has `nologin` as its shell, so use your own admin account for the editing and `chown deploy:deploy` afterwards.)

Generate the three high-entropy secrets with:

```bash
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 48   # CONSENT_ORIGIN_TOKEN_SECRET
openssl rand -base64 48   # IP_HASH_SALT
```

### 9.3 What differs between dev, prd and a workstation

Everything else is identical — same images, same compose structure, same Spring configuration.

| Variable | workstation | dev | prd |
|----------|-------------|-----|-----|
| `SPRING_PROFILES_ACTIVE` | `api,dev` | `api,dev` | `api,prd` |
| `COOKIE_SECURE` | **`false`** | `true` | `true` |
| `APP_BASE_URL` | `http://localhost:3000` | `https://dev.cookiekeeper.eu` | `https://app.cookiekeeper.eu` |
| `API_BASE_URL` | `http://localhost:8080` | `https://api.dev.cookiekeeper.eu` | `https://api.cookiekeeper.eu` |
| `CDN_BASE_URL` | `http://localhost:8081` | `https://cdn.dev.cookiekeeper.eu` | `https://cdn.cookiekeeper.eu` |
| `MAIL_PROVIDER` | `smtp` (Mailpit) | `smtp` (Mailpit) | **`brevo`** |
| `BREVO_API_KEY` | not read | not read | `xkeysib-…` |
| Stripe keys | `sk_test_…` | `sk_test_…` | `sk_live_…` |
| `STRIPE_AUTOMATIC_TAX` | `false` | `false` | `true` |
| `SENTRY_ENVIRONMENT` | `workstation` | `dev` | `prd` |
| `SENTRY_DSN_BACKEND` | blank | EU DSN | EU DSN |
| `DB_URL` | `…//postgres:5432/cookiekeeper` (container) | `…//10.20.10.20:5432/cookiekeeper?sslmode=verify-ca&sslrootcert=/etc/ssl/pgca.crt` | same, `10.20.20.20` |
| `DB_PASSWORD` | `cookiekeeper` | dev's, set in §11.1 | prd's — **a different one** |
| `CADDY_ENV` (caddy dir) | n/a | `dev` | `prd` |

`COOKIE_SECURE` is the one setting that exists purely for the workstation case: a browser silently discards a `Secure` cookie delivered over `http://localhost`, so login would appear to succeed and never persist. It defaults to `true`, so forgetting it in a deployed `.env` is safe — the unsafe value has to be typed deliberately.

A test-mode Stripe price id is invalid against a live key and vice versa, so never copy Stripe values between dev and prd.

---

## 10. Docker Compose stacks

### 10.1 The compose files

They live in the repo, not on the server — the deploy job copies them across, which is what stops config from drifting between what you can read and what is running.

| File | Compose project | Where it runs |
|------|-----------------|---------------|
| `infra/compose.workstation.yml` | `cookiekeeper-workstation` | your machine (builds from source, publishes ports, includes a `postgres` container) |
| `infra/compose.yml` | `cookiekeeper-dev` / `cookiekeeper-prd` | both app hosts → `/opt/cookiekeeper/<env>/` |
| `infra/caddy/compose.caddy.yml` | `cookiekeeper-caddy` | both app hosts → `/opt/cookiekeeper/caddy/` |

**One file serves both environments.** It was two, and they were 90% identical; the four things that actually differed are now inputs rather than duplicated text:

| Difference | How it is expressed |
|------------|---------------------|
| Compose project name | `--project-name cookiekeeper-<env>`, from the argument |
| Spring profile | `SPRING_PROFILES_ACTIVE: api,${ENV_NAME}` |
| Container subnet | `${APP_SUBNET}` — derived from `<env>` by `deploy.sh`, never hand-set |
| Mailpit | `profiles: ["dev"]`; `deploy.sh` passes `--profile dev` only for dev |

`ENV_NAME` and `APP_SUBNET` use `${VAR:?message}`, so running `docker compose` by hand without them fails with a named error instead of quietly starting the wrong thing. The same reasoning already applies to `infra/scripts/egress-firewall.sh`, which lists both environments' subnets so it is byte-identical on both machines: two near-identical files drift, and the resource ceilings below are only a useful signal while dev and prd genuinely match.

The deployed stack runs three services — `api`, `scanner`, `dashboard` — on its own default network, with `api` and `dashboard` additionally joined to the shared external `caddy-net` so Caddy can reach them. Dev adds `mailpit` (§7.6).

> **If you deployed before this change:** the app hosts will still have a stale `compose.dev.yml` / `compose.prd.yml` in `/opt/cookiekeeper/<env>/`. Nothing reads them — `deploy.sh` names `compose.yml` explicitly — but delete them so the next person debugging on the box does not read the wrong file. On a host that has never been deployed to, there is nothing to clean up.

**There is no `postgres` service in either deployed stack** (ADR-24). The database is bare Postgres on its own machine, reached over the private network; `pgca.crt` next to the `.env` is bind-mounted into `api` and `scanner` at `/etc/ssl/pgca.crt` so the JDBC URL's `sslmode=verify-ca` has something to pin. The workstation file is the one place a Postgres container remains — nobody provisions a second machine to run tests on a laptop.

### 10.2 Resource limits

Every service in both deployed stacks carries `mem_limit`, `memswap_limit`, `cpus`, `cpu_shares` and `pids_limit`, sized for **one environment alone on a CX22** (2 vCPU / 4 GB) with ~900 MB left for the host, dockerd and Caddy:

| Service | Memory | CPU ceiling | Weight |
|---------|--------|-------------|--------|
| `api` | 1280 MB | 2.0 | 2048 |
| `scanner` | 1408 MB | 1.0 | 512 |
| `dashboard` | 384 MB | 1.0 | 1024 |
| `mailpit` (dev only) | 128 MB | 0.5 | 256 |
| `caddy` (own project) | 256 MB | 1.0 | 1024 |

The point is not to cap usage for its own sake — it is to decide *in advance* which container dies when the box runs out. Without limits the kernel's OOM killer picks by heuristic, and its favourite victim is the largest resident process. With them, the scanner (lowest weight, most elastic appetite, Chromium) absorbs the pressure, and `ScanQueue`'s visibility-timeout lease means a job the killed worker was holding is reclaimed rather than stuck in `running`.

Note what the split changed here: the database can no longer be the OOM victim, because it is not on this machine. That was the strongest argument for moving it — a Postgres killed by a neighbour's memory leak is an outage plus a crash-recovery window, and no container limit could prevent it while they shared a kernel.

The database host's own sizing lives in `cloud-init-db.yaml` (`shared_buffers = 1GB`, `effective_cache_size = 3GB`, `work_mem = 8MB`, `max_connections = 50`) and assumes the whole machine. `max_connections` is the ceiling the app's Hikari pools are budgeted against: `api` and `scanner` each open up to `DB_POOL_MAX` (10), leaving ample room for a `psql` session during an incident.

Three details worth knowing before you change any of them:

- **`JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=70`** on `api` and `scanner`. JDK 21 reads the cgroup limit but defaults to a 25% heap — a 256 MB heap inside a 1 GB container. 70% leaves headroom for metaspace, code cache, thread stacks and direct buffers, which sit outside the heap and still count against `mem_limit`.
- **`shm_size: 512mb`** on `scanner`. Chromium keeps renderer shared memory in `/dev/shm`, which Docker sizes at 64 MB. A content-heavy page overruns it and the tab dies with a bare "Target crashed" — a scan failure that reads like the customer's site is broken.
- **dev and prd are deliberately identical.** Dev is where you find out prd's limits are too tight, and it cannot do that with more headroom than prd. Since §10.1 that is structural rather than a convention to remember: resize the box → edit `infra/compose.yml` once and re-run the k6 smoke test in `infra/load/`. Editing it on the server does nothing lasting — the deploy job copies it from the repo on every run.

The compose **project name** is load-bearing: Docker derives container DNS names from it, so `cookiekeeper-prd-api-1` is the hostname the Caddyfile proxies to. Renaming the project without updating the Caddyfile produces a 502 that looks like an application failure but is a DNS miss. (This exact mismatch existed in the repo and was fixed as part of the CookieKeeper rebrand.)

### 10.3 Images and tags

```yaml
image: ghcr.io/${GHCR_OWNER}/cookiekeeper-api:${IMAGE_TAG}${API_DIGEST-}
```

`GHCR_OWNER` comes from the hand-maintained `.env`; the rest come from `.env.deploy`, which `deploy.sh` rewrites on every deploy. The two environments run the same three images and differ only by environment variables.

`API_DIGEST` (and its scanner/dashboard siblings) is **empty on dev and `@sha256:…` on prd**. `repo:tag@sha256:…` is a valid OCI reference and Docker pulls the *digest*, so the tag is only a label. `verify-images` resolves each tag to a digest once and the deploy job pins that exact digest, so the three prd hosts' `pull`s cannot disagree with each other or with what the workflow checked. Dev passes no digests, because dev is where the images are built and there is nothing to promote from.

**Be precise about what this guarantees.** It closes the window between *resolving* a tag and *running* it — a repoint during the release, a concurrent re-run, a retried job. It does **not** prove production runs the bytes dev exercised: `verify-images` reads the tag at prd-release time, so if the tag was repointed in GHCR *after* the dev release, it faithfully pins the new digest and reports success. Closing that wider window needs the digest recorded at `release-dev` time and compared here (tag annotation, GHCR label, or a build attestation) — worth doing, not done yet. GHCR tag immutability would also close it.

### 10.4 What the deploy actually does

`infra/scripts/deploy.sh <dev|prd> <tag>`, with the optional `API_DIGEST` / `SCANNER_DIGEST` / `DASHBOARD_DIGEST` in the environment. It runs in four phases: refuse, deploy, verify, recover.

#### Phase 1 — the refusals

Every one of these aborts before a single container is touched, and exits non-zero:

| Guard | Why it exists |
|-------|---------------|
| `hostname -s` must be `cookiekeeper-<env>-app` | A swapped `DEV_SSH_HOST`/`PRD_SSH_HOST` secret would otherwise deploy the production tag onto dev, silently and green. Override for testing with `DEPLOY_EXPECT_HOST`. |
| `/usr/local/sbin/cookiekeeper-egress-firewall` must exist and list this `APP_SUBNET` | An unfiltered container network must never come up quietly (ADR-18). **Fatal**, not a warning. Escape hatch: `DEPLOY_ALLOW_UNFILTERED=1`, which downgrades it to a loud warning. |
| dev must **not** be `MAIL_PROVIDER=brevo`; prd must **be** `brevo` | Constraint #4. Backwards on dev, a test signup mails a real person and burns Brevo's sending reputation. Unset on prd sends production mail nowhere. |
| prd must have no `mailpit` container from a previous run | `up --wait` does **not** remove an already-running container just because its profile is now disabled. Without this check, one bad deploy leaves Mailpit in production indefinitely. |
| `.env` must contain `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, … | `compose.yml` guards these with `${VAR:?}`, so a missing secret is a named error rather than a container that boots with an empty password. |

#### Phase 2 — the deploy

```bash
cat > .env.deploy <<EOF                           # the only file automation writes
IMAGE_TAG=…  ENV_NAME=…  APP_SUBNET=…  API_DIGEST=…  SCANNER_DIGEST=…  DASHBOARD_DIGEST=…
EOF
docker compose --project-name cookiekeeper-<env> --file compose.yml \
               --env-file .env --env-file .env.deploy pull
docker compose … up -d --remove-orphans --wait --wait-timeout 240
docker image prune -af --filter "until=168h"      # the 40GB disk needs this
```

> **Compose reads the shell environment *before* `--env-file`.** This is the single most important thing to know before editing this script. Writing a value into `.env.deploy` does **not** make it win — a variable of the same name already in the shell beats it. That is why `deploy.sh` `export`s every value it derives instead of relying on the file. A stale `API_DIGEST` in the environment once rendered `repo:tag` immediately followed by `sha256:…` with the `@` stripped: an invalid reference that fails at `pull`. If you add a variable to `.env.deploy`, export it too, or it is decorative.

Dev sets `COMPOSE_PROFILES=dev` (Mailpit); prd exports it **empty**, rather than just omitting it, for the same precedence reason. Flyway migrates on `api` container boot — there is no separate migration step.

The prune filters on image **creation** time, not pull time, so a rollback target built more than a week ago will have been pruned. `up` re-pulls it automatically, so the rollback still works, just more slowly.

#### Phase 3 — the health gate

`api` and `dashboard` carry healthchecks, so `--wait` does not return until they are actually serving. `api`'s probe is `/actuator/health`, which includes the DataSource indicator, so an unreachable database fails the deploy instead of leaving a container that is "running" and answering 503. `scanner` has no healthcheck on purpose: it exposes no ports at all (SSRF posture), so there is nothing to probe — a scanner that boots and dies is caught by the queue's visibility timeout instead.

**`--wait` alone is not trusted.** It returns exit 0 on the timeout path often enough to matter, so `deploy.sh` re-inspects every container afterwards and fails if any is not `running`, or has a health status that is neither `healthy` nor `none`. A deploy is green only if both checks agree.

#### Phase 4 — recovery

On failure the script writes `deploy-failure-<timestamp>.log` next to the `.env` — `ps` output plus the last 50 log lines per service — at mode `0600`. It is deliberately **not** sent back to CI: container logs can contain request data, and CI logs are far more widely readable than the box.

Then it rolls back to the **last known good** deploy, which is `.env.deploy.lastgood` plus `compose.yml.lastgood` — both snapshotted only *after* a deploy has passed Phase 3. The distinction matters: rolling back to "the previous deploy" would happily restore a release that never came up either. Two cases behave differently on purpose:

- **prd with no digest pins recorded** — refuses to roll back. An unpinned prd rollback would resolve tags afresh and could run bytes nobody verified.
- **No `.env.deploy.lastgood` at all** (a host that has never had a successful deploy) — prints `no distinct last-known-good deploy to roll back to`, leaves the stack up for inspection, and exits 1. It does not roll back into a void. **This is the normal path for your very first deploy**, which by definition has nothing behind it.

The script exits non-zero whether or not the rollback succeeds, so the workflow still fails. A rollback restores service; it does not hide the failure.

> **The rollback is the application only.** Flyway is forward-only and already ran on the new image's boot; it is **not** undone. This recovers the common case — a build that will not start — and for a bad migration it buys you an older app against an already-migrated schema, which may itself be wrong. Read the migration before trusting a green rollback.

---

## 11. Database, Migrations and Backups

### 11.1 The one first-time database step: set the role password

Each environment has its own Postgres 16 running bare on its own machine, installed and configured by `cloud-init-db.yaml`. It creates the `cookiekeeper` database and role — deliberately **without a password**, because every Terraform variable (including `user_data`) is stored in state in plaintext. Combined with a `pg_hba.conf` whose only network line is `scram-sha-256`, that means nothing can authenticate until an operator acts. It fails closed.

So, once per database host:

```bash
ssh root@<db public ip>
pw="$(openssl rand -base64 32)"
runuser -u postgres -- psql -v ON_ERROR_STOP=1 -c "ALTER ROLE cookiekeeper PASSWORD '$pw';"
printf 'DB_PASSWORD=%s\n' "$pw"       # → the APP host's .env, and your password manager
```

Then hand the app host the database's certificate, because the JDBC URL uses `sslmode=verify-ca` against a self-signed certificate that is its own CA:

```bash
scp root@<db public ip>:/etc/postgresql/ssl/server.crt ./pgca-<env>.crt
scp ./pgca-<env>.crt deploy@<app public ip>:/opt/cookiekeeper/<env>/pgca.crt
```

Only the certificate travels; `server.key` never leaves the database host. `verify-ca` rather than `verify-full` because the URL names an IP, and `verify-full` would want a hostname to match.

Verify the four layers that actually restrict 5432 — a Hetzner cloud firewall is **not** among them, because it filters public traffic only and cannot see the private network at all:

```bash
runuser -u postgres -- psql -Atc 'SHOW listen_addresses'   # localhost,10.20.x.20 — never 0.0.0.0
ufw status | grep 5432                                     # from the app host's private IP only
grep -c '^host ' /etc/postgresql/16/main/pg_hba.conf       # 0 — every network line is hostssl
nmap -Pn -p 5432 <db public ip>                            # closed/filtered from outside
```

**Schema** comes from Flyway, which runs inside the `api` container during Spring context startup. There is no manual migration command and no init SQL to apply. A deploy that changes the schema applies it by starting the new image; if a migration fails the container fails its healthcheck and the old one keeps serving.

Checking that it worked, from the **app** host:

```bash
ssh root@<app public ip>
cd /opt/cookiekeeper/dev

# Flyway's own record of what has been applied — psql runs on the app host
# (postgresql-client-16 is preinstalled) against the database over the private network.
PGPASSWORD="$(grep '^DB_PASSWORD=' .env | cut -d= -f2-)" \
  psql "postgresql://cookiekeeper@10.20.10.20:5432/cookiekeeper?sslmode=verify-ca&sslrootcert=/opt/cookiekeeper/dev/pgca.crt" \
  -c 'select installed_rank, version, description, success from flyway_schema_history order by installed_rank desc limit 5;'

# The app's view
curl -fsS http://localhost:8080/actuator/health
```

Two things people expect to be setup steps but are not: the cookie signature data ships as a Flyway migration, and the `consent_events` monthly partitions are created ahead of time by a scheduled job in the app, not by hand.

### 11.2 Backups — `infra/scripts/backup.sh`

Do **not** write a backup script; the repo has one, and it is stricter than anything worth improvising. It runs **on the database host**, from root's crontab — one environment per machine. That placement is half the point of the split: `pg_dump` goes over the local unix socket under `peer` auth, so the plaintext dump never crosses a network and there is no password anywhere in the path — and the app host, which is the box running a web crawler, never holds a credential that can read every table.

It pipes `pg_dump | gzip | age` in a single pipeline so plaintext never touches disk, writes a SHA-256 sidecar, copies both to Hetzner Object Storage with rclone, and rotates local (14d) and off-site (90d) copies. It refuses to run if `age` is missing, if `BACKUP_AGE_RECIPIENT` is unset, if Postgres is not answering on the socket, or if it cannot tell which environment it is on.

That last check is `BACKUP_ENV`, derived from `hostname -s` (`cookiekeeper-prd-db` → `prd`). It is fatal rather than defaulted because guessing wrong would file production's dump under dev's name.

The encryption is to a **public** recipient key. The host can write backups and can never read them, so compromising a server does not hand over the consent-log history. The matching private identity lives offline in your password manager and is only ever supplied during a restore drill.

Configure it once per database host in `/opt/cookiekeeper/backup.env` (root-owned, `chmod 600`), which the cron line sources so keys stay out of the crontab:

```bash
BACKUP_AGE_RECIPIENT=age1...            # public key; add a second, space-separated, for break-glass
BACKUP_RCLONE_REMOTE=hetzner-s3:cookiekeeper-backups
BACKUP_LOCAL_RETENTION_DAYS=14
BACKUP_OFFSITE_RETENTION_DAYS=90
```

```cron
15 3 * * * set -a; . /opt/cookiekeeper/backup.env; /opt/cookiekeeper/backup.sh >> /var/log/cookiekeeper-backup.log 2>&1
```

Both hosts may share one bucket. `backup.sh` appends a per-environment prefix (`<remote>/dev`, `<remote>/prd`) **in the script**, not from config, and scopes both the upload and the `rclone delete --min-age` prune to it — otherwise dev's nightly cron would prune production's only off-site copy, and it would do so silently.

Generate the key pair on your workstation, not on a server — no server must ever hold the identity:

```bash
age-keygen -o age-identity.txt     # keep OFFLINE, in your password manager
grep 'public key' age-identity.txt # this is BACKUP_AGE_RECIPIENT
```

The rclone remote is a Hetzner Object Storage bucket in an EU region (Falkenstein or Nuremberg — constraint #2; the bucket is the same provider as the servers, so it adds no new data processor). Create it in the Hetzner Cloud console under **Object Storage**, generate S3 credentials, and configure rclone as an `s3` remote with `provider = Other` and the Hetzner endpoint. `backup.sh` reads the configured endpoint back and **refuses to upload** to one it can identify as non-EU.

### 11.3 Restore drill — `infra/scripts/restore-drill.sh`

A backup you have never restored is a hope. Run the drill on the database host that owns the data — prd's drill belongs on prd's box, since restoring production PII onto the dev machine would put it on the lower-trust host. Before launch, and quarterly after:

```bash
ssh root@<db public ip> 'install -m600 /dev/null /dev/shm/id.txt'   # create 0600 FIRST
scp age-identity.txt root@<db public ip>:/dev/shm/id.txt
ssh root@<db public ip>
set -a; . /opt/cookiekeeper/backup.env; set +a
/opt/cookiekeeper/restore-drill.sh --identity /dev/shm/id.txt \
  --from-offsite cookiekeeper-prd-<ts>.sql.gz.age
rm -f /dev/shm/id.txt      # shred is ineffective on tmpfs; rm is what removes it
```

`--env` defaults to this host's own environment, so you rarely pass it. The script pulls the dump (newest local by default, or the named off-site object), verifies the checksum, decrypts and loads it into a throwaway scratch database on the same Postgres, runs sanity queries, prints row counts, and drops the scratch DB — with `WITH (FORCE)`, so restored PII cannot survive behind a stuck session. The live database is never touched.

Drilling `--from-offsite` at least once is the point: it proves the rclone copy is real, the object is intact in transit, **and** the recipient key is actually decryptable — a typo'd public key encrypts perfectly and produces backups nobody can ever open.

---

## 12. Caddy & CDN Hosting Explained

### 12.1 How Caddy routes requests

Caddy is one container per **application host** (`infra/caddy/compose.caddy.yml`, compose project `cookiekeeper-caddy`) that owns ports 80/443 on that machine and serves only that environment's hostnames. It is deliberately not part of the environment's own stack: a failed application deploy must not be able to take TLS down with it, and Caddy holds the ACME account and certificates, which should outlive any single release.

Which vhosts it serves is decided by one file — `CADDY_ENV` in `/opt/cookiekeeper/caddy/.env` selects `Caddyfile.dev` or `Caddyfile.prd`. Both import the same `snippets.caddy` (headers, the consent body cap, the CDN cache rules), so the shared policy lives in one place and only the hostnames and upstreams differ. `CADDY_ENV` is referenced as `${CADDY_ENV:?}`: unset it and the container refuses to start rather than starting with an empty or default configuration.

```
Visitor browser
    │ HTTPS
    ▼
Cloudflare (proxied — caches whatever Cache-Control allows)
    │ HTTPS
    ▼
cookiekeeper-prd-app :443 → Caddy (Caddyfile.prd), routing by hostname over `caddy-net`
    │
    ├─ app.cookiekeeper.eu     /api/v1/* → cookiekeeper-prd-api-1:8080
    │                          everything else → cookiekeeper-prd-dashboard-1:3000
    ├─ api.cookiekeeper.eu     → cookiekeeper-prd-api-1:8080
    └─ cdn.cookiekeeper.eu     /cfg/*    → cookiekeeper-prd-api-1:8080
                               everything else → static files from /srv/cdn/prd

cookiekeeper-dev-app :443 → Caddy (Caddyfile.dev)
    └─ dev.…, api.dev.…, cdn.dev.…  → the identical shape against cookiekeeper-dev-*
```

Since the split (ADR-24) the two environments no longer share an edge at all. A dev certificate renewal storm, a dev config typo, or a dev host reboot is invisible to production — which is the property that made the previous single-box arrangement uncomfortable, because one Caddyfile served both and every dev edit was a production edit away from a mistake.

Two details worth internalising:

- **Container names are the routing table.** Docker derives DNS names from the compose project name, so `cookiekeeper-prd-api-1` only resolves because the project is named `cookiekeeper-prd`. Rename the project and you get a 502 that looks like an application crash but is a DNS miss.
- **The dashboard calls `/api/v1/*` same-origin.** That is why one dashboard image serves both environments: no API URL is baked in at build time — Caddy decides which API answers based on which hostname was asked.

Caddy also enforces two things at the edge, before a request reaches any app: the shared security-header block (HSTS, `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `Permissions-Policy`, `-Server`) and an 8 KB body cap on the public `POST /api/v1/consent` path. A legitimate consent event is a few hundred bytes; the Jackson stream limits in the backend are the in-process backstop, not the front line. CSP is deliberately **not** set in Caddy — the dashboard emits a per-request nonce-based CSP itself, and the widget must stay embeddable anywhere.

### 12.2 The widget bundle — static files on the CDN vhost

`cdn.<env>.cookiekeeper.eu` serves the widget from disk at `/srv/cdn/<env>` on that environment's app host, mounted read-only into Caddy. Nothing dynamic is involved: `release-dev` (and `release-prd`) build the widget, run the 20 KB gzip size gate, and `scp` the output into that directory. The API is never in the path for the bundle itself.

Caching is split on purpose:

| Path | Cache-Control | Why |
|------|---------------|-----|
| `/v1.js` (the stable embed URL) | `max-age=3600, stale-while-revalidate=86400` | Overwritten in place by every deploy. An immutable TTL here would pin a buggy widget in browsers and at the Cloudflare edge for a year with no cache-busting lever left. |
| everything else | `max-age=31536000, immutable` | Content-hashed filenames — a new build is a new URL, so immutable is safe. |

The customer's embed is a single tag:

```html
<script src="https://cdn.cookiekeeper.eu/v1.js" data-complyr="pk_live_123" async></script>
```

### 12.3 Per-site config — `/cfg/{siteKey}.json`

This one path on the CDN vhost is *not* a file. Caddy proxies `/cfg/*` to the API, which renders the published banner config live (ADR-19). The `Cache-Control: public, max-age=300` is set by the API on the response, so the TTL lives next to the code that knows when a config changes — and the CORS header for that route is emitted by Spring, not Caddy, because two `Access-Control-Allow-Origin` headers on one response is a browser error.

The effect is that Cloudflare absorbs almost all of the read load (the widget runs on every visitor page view of every customer site), while a customer's banner change still goes live within five minutes without a deploy.

### 12.4 Hosted policy pages — `/p/{publicId}`

The policy URL we hand customers is on the **dashboard** vhost, not the CDN:

```html
<a href="https://app.cookiekeeper.eu/p/pub_xyz">Cookie Policy</a>
```

Caddy sends it to the dashboard container like any other non-`/api/v1` path. Next.js rewrites the locale-less `/p/{publicId}` onto the locale-scoped route so it renders inside the i18n layout; the page then fetches the policy content from the public API on the same origin, with `?lang` as URL state so a link is shareable in a specific language. There is no special cache rule for it.

### 12.5 TLS

Caddy obtains and renews Let's Encrypt certificates automatically over ACME; the `caddy-data` volume holds them, and losing that volume just causes a re-issue. Because the certificate is publicly trusted, Cloudflare's **Full (strict)** mode is satisfied with no extra work — the leg from Cloudflare to the origin is encrypted and validated.

Full (strict) is the setting that matters. *Flexible* would leave Cloudflare→origin in plaintext across the public internet while showing visitors a padlock, which for a GDPR product is the worst combination available. Terraform sets this (§4), so it cannot be quietly changed in the dashboard without the next `terraform apply` putting it back.

The alternative — a Cloudflare Origin Certificate (15-year, trusted only by Cloudflare) plus an explicit `tls` directive in the Caddyfile — is a valid setup and slightly reduces moving parts, but it makes the origin unreachable except through Cloudflare and it takes ACME renewal out of the picture. We do not use it; if you switch, add the cert files to the Caddy container and the `tls` directive to each vhost block, and note it in an ADR.

---

## 13. Checklist Before Launch

### 13.1 Terraform and infrastructure

- [ ] Hetzner Object Storage bucket for Terraform state created, S3 credentials generated (§3.3)
- [ ] `backend.hcl` / backend config filled in; `terraform init` succeeds in **both** modules
- [ ] `platform/` applied by hand; `terraform output servers` lists **four** machines, all `running`
- [ ] Both private networks exist and each host has the private IP the map claims (`ip -4 addr show enp7s0`)
- [ ] `environments/` applied for workspace `dev` **and** workspace `prd`, each pointing at its own app IP
- [ ] `.terraform.lock.hcl` committed for both modules (CI resolves the same provider builds)
- [ ] All four hosts hardened per `infra/scripts/server-setup.md`: SSH key-only, no root password login, fail2ban, unattended-upgrades
- [ ] On each **app** host: Docker installed, `caddy-net` present with `--ip-range 10.31.30.128/25`, Caddy stack up (`cookiekeeper-caddy`) with the right `CADDY_ENV`
- [ ] On each **db** host: `cookiekeeper` role password set by hand, TLS cert generated, `server.crt` copied to the matching app host as `pgca.crt`
- [ ] Hetzner Object Storage bucket for **backups** created, EU region, separate credentials from the state bucket

### 13.2 DNS, TLS and Cloudflare

- [ ] Domain registered; Cloudflare nameservers live at the registrar
- [ ] `CLOUDFLARE_ZONE_ID` recorded; API token scoped per §4 and stored as an Actions secret
- [ ] All six hostnames resolve and are proxied (orange cloud) — Terraform owns these records
- [ ] SSL/TLS mode is **Full (strict)** (Terraform sets it)
- [ ] `curl -I https://api.dev.cookiekeeper.eu/actuator/health` returns 200 over a valid certificate

### 13.3 Third-party accounts

- [ ] Stripe account activated; **live** keys available and Tax configured (or `STRIPE_AUTOMATIC_TAX=false`)
- [ ] Stripe Products/Prices created in **both** modes; test ids in dev, live ids in prd — never mixed
- [ ] Stripe webhook endpoints registered for `api.dev.…` and `api.…`, each with its own signing secret
- [ ] Brevo account provisioned in the **EU data region** (ADR-14), sender domain verified (SPF/DKIM/DMARC)
- [ ] Sentry project created with an **EU-region DSN** (`*.de.sentry.io`) — the app refuses anything else
- [ ] `support@cookiekeeper.eu` and `no-reply@cookiekeeper.eu` exist and are monitored

### 13.4 GitHub

- [ ] Repository secrets set: `DEV_SSH_HOST`/`DEV_SSH_USER`/`DEV_SSH_KEY`, `PRD_SSH_HOST`/`PRD_SSH_USER`/`PRD_SSH_KEY`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ZONE_ID`, `TFSTATE_ACCESS_KEY_ID`, `TFSTATE_SECRET_ACCESS_KEY` (§8.5)
- [ ] `DEV_SSH_HOST` and `PRD_SSH_HOST` are the **app** hosts' public IPs — CI never touches a database host
- [ ] `production` environment created with required reviewers — this is the only manual gate on prd
- [ ] GHCR packages visible to the repo; the deploy user can `docker login ghcr.io` on **both** app hosts
- [ ] A `release-dev` run has completed end to end at least once (tag pushed, images built, dev green)

### 13.5 Configuration on the host

- [ ] `/opt/cookiekeeper/<env>/.env` written by hand on each app host, `chmod 600`, owned by the deploy user
- [ ] Every variable in `.env.example` accounted for in both files
- [ ] `JWT_SECRET`, `IP_HASH_SALT`, `CONSENT_ORIGIN_TOKEN_SECRET` generated fresh per environment (`openssl rand -base64 48`) — **never** shared between dev and prd
- [ ] `DB_URL` names the **private** IP (`10.20.10.20` / `10.20.20.20`) and carries `sslmode=verify-ca&sslrootcert=…/pgca.crt`; `pgca.crt` is present and readable by the container
- [ ] `DB_PASSWORD` matches the password actually set on the matching database host, and differs between environments
- [ ] `MAIL_PROVIDER=smtp` on dev (Mailpit) and `brevo` on prd — the wrong way round on dev mails real people
- [ ] `CADDY_ENV` set in `/opt/cookiekeeper/caddy/.env` on each app host, matching that host's environment
- [ ] `COOKIE_SECURE=true` in both (only a workstation sets it false)
- [ ] `SENTRY_ENVIRONMENT` set to `dev` / `prd` so laptop noise never lands in a real environment's issues

### 13.6 Backups

- [ ] age key pair generated on the workstation; the **identity is offline** and the recipient is in `backup.env`
- [ ] `/opt/cookiekeeper/backup.env` written on **each database host**, `chmod 600`; cron line installed as root
- [ ] `hostname -s` on each database host resolves to the right environment (`backup.sh` derives `BACKUP_ENV` from it and refuses to guess)
- [ ] rclone remote configured against the EU backups bucket on both database hosts; one backup run completed on each
- [ ] Off-site listing shows the per-environment prefixes (`…/dev/`, `…/prd/`) — a shared prefix means one host's prune deletes the other's copies
- [ ] `restore-drill.sh --from-offsite` run successfully on each database host, identity removed (`rm -f`) afterwards

### 13.7 Monitoring and security

- [ ] Uptime monitoring live (two layers — see `infra/monitoring/uptime.md` and `uptime-check.sh`)
- [ ] Container egress firewall installed and `egress-firewall.sh verify` passes on **both app hosts** (ADR-18), with `EGRESS_DB_TARGETS` pointing at that environment's database only
- [ ] Database 5432 verified closed from the public internet and open only to the matching app host's private IP (§11.1) — a Hetzner cloud firewall cannot do this, so check `listen_addresses`, `ufw` and `pg_hba.conf` directly
- [ ] Load smoke test run once against dev (`infra/load/README.md`) so the CX22 tuning is not theoretical
- [ ] Rate limiting active at both layers: Cloudflare ruleset (Terraform) and the in-app filters
- [ ] Widget size gate green in CI (≤20 KB gzipped) — it is a release blocker, not a warning

---

## 14. Scaling Path

Production runs on two CX22s — one application host, one database host — with dev's identical pair alongside it (ADR-24). The environment split and the database split are already done, so the next moves are all *within* that shape. It holds for a long time: the widget's read path is absorbed by Cloudflare, and consent writes are small.

In rough order of what to reach for:

1. **Vertical first.** CX32/CX42 on whichever host is actually saturated — and now that they are separate machines you can tell which. Resizing is a Terraform `server_type` change plus a reboot. Re-run `infra/load/smoke.js` and retune `DB_POOL_MAX` / `SERVER_TOMCAT_MAX_THREADS` together; the pool sum across all app containers must stay under the database's `max_connections`.
2. **Horizontal scanners.** Scan jobs are claimed with `SKIP LOCKED`, so adding scanner containers needs no coordination logic — only enough CPU for the extra Playwright browsers. If scanning starts crowding the API on the app host, the next step is a third machine running only the `scanner` profile, joined to the same private network. Nothing in the code changes.
3. **A read replica or a bigger database box.** Analytics rollups are the first thing to feel the strain. Streaming replication to a second Postgres host is a configuration change on machines we already own — much less disruptive than it would have been when Postgres was a container sharing a disk with everything else.
4. **Managed Postgres.** Only when point-in-time recovery and failover matter more than the €7/mo. Keep it EU-region; a non-EU managed database is a constraint #2 violation and needs an ADR before it is even prototyped.
5. **Config at the edge.** Moving `/cfg/*` to Cloudflare Workers KV cuts the last per-visitor path that touches our origin. Worth it only if the config endpoint is measurably hot after Cloudflare caching.

The thing not on this list is running dev and prd on one box again to save €15/mo. That was the previous arrangement; it made every dev experiment a production risk, and undoing it is the point of ADR-24.

---

## 15. Troubleshooting

**First decide which machine you are on.** Four hosts now, and half the commands below only exist on one kind:

| Symptom class | Host |
|---|---|
| Containers, deploys, Caddy, TLS, the widget bundle | `cookiekeeper-<env>-app` |
| Postgres itself, backups, restore drills | `cookiekeeper-<env>-db` |

On the **app** host, the compose invocation is always project-scoped:

```bash
cd /opt/cookiekeeper/<env>
dc() { docker compose --project-name "cookiekeeper-<env>" --file compose.yml \
       --env-file .env --env-file .env.deploy "$@"; }
# on dev only, prefix with COMPOSE_PROFILES=dev or mailpit is invisible to it:
#   COMPOSE_PROFILES=dev dc ps
```

**Never set `COMPOSE_PROFILES=dev` on the prd box, and never put it in a `.env`.** It activates the profile with no `--profile` flag anywhere, and `up --remove-orphans` will *not* remove a profile-disabled container that is already running — so a Mailpit started on prd stays up until someone deletes it by hand, quietly swallowing real customer mail. `deploy.sh` exports `COMPOSE_PROFILES=""` on prd specifically to override a stray value in `.env`, and aborts if it finds a `mailpit` container in the prd project.

Both `--env-file`s are required: `.env.deploy` carries `ENV_NAME` and `APP_SUBNET`, and compose refuses to render without them rather than guessing. If `dc` reports `required variable APP_SUBNET is missing a value`, you dropped that flag — not a broken deploy.

There is no `postgres` service in that project — `dc ps` listing three containers (`api`, `dashboard`, `scanner`) is correct, not a crash.

### `deploy.sh` refused to start (nothing was touched)

The common first-deploy case. Every Phase-1 guard (§10.4) aborts before any container changes, and names itself in the message:

| Message contains | Fix |
|------------------|-----|
| `refusing to deploy … this is not cookiekeeper-<env>-app` | You are on the wrong box, or `DEV_SSH_HOST`/`PRD_SSH_HOST` are swapped in the Actions secrets |
| `cannot confirm container egress is filtered` | Install the egress firewall (`server-setup.md` §3). To deploy anyway — **only** knowingly, on dev — re-run with `DEPLOY_ALLOW_UNFILTERED=1` |
| `MAIL_PROVIDER=brevo` on dev | Set `MAIL_PROVIDER=smtp` in `/opt/cookiekeeper/dev/.env`. Dev mail belongs in Mailpit (§7.6) |
| `expected 'brevo'` on prd | Set `MAIL_PROVIDER=brevo` in `/opt/cookiekeeper/prd/.env` |
| `mailpit ... in prd` | A previous run left the container behind. `docker rm -f cookiekeeper-prd-mailpit-1`, then redeploy |
| `missing from .env` | That secret is absent from the hand-written `.env` — see §9.2 for the full list |

### A deploy failed and rolled back (or refused to)

`deploy-failure-<timestamp>.log` is written next to the `.env`, mode `0600`, containing `ps` plus the last 50 log lines per service. It is deliberately **not** returned to CI — container logs can carry request data. Read it on the box:

```bash
ls -t /opt/cookiekeeper/<env>/deploy-failure-*.log | head -1 | xargs less
```

Three outcomes are normal, depending on what the host had before:

- **Rolled back** — the previous healthy release is running again. The workflow still fails, on purpose; the rollback restores service without hiding the breakage.
- **`no distinct last-known-good deploy to roll back to`** — nothing has ever deployed successfully here, so the stack is left up for inspection. Expected on a first deploy.
- **prd refused to roll back** — the recorded good deploy has no digest pins, and an unpinned prd rollback could run bytes nobody verified. Promote a known-good tag with `release-prd` instead.

### A deploy finished but the site is unchanged

Check which tag is actually running — `deploy.sh` writes it, and a failed pull leaves the old container up:

```bash
cat .env.deploy
dc ps
dc images
```

### 502 from Caddy

Almost always container-name resolution, not the app. Caddy proxies to `cookiekeeper-<env>-api-1` / `-dashboard-1`, which only exist if the compose project is named exactly `cookiekeeper-<env>` and the containers are attached to `caddy-net`:

```bash
docker ps --format '{{.Names}}\t{{.Status}}'
docker network inspect caddy-net --format '{{range .Containers}}{{.Name}} {{end}}'
docker compose -f /opt/cookiekeeper/caddy/compose.caddy.yml --project-name cookiekeeper-caddy logs --tail 100
```

### Certificate errors

Caddy issues via ACME; failures are visible in its log. The usual causes are the DNS record not resolving to this host yet, port 80 blocked (HTTP-01 needs it even behind Cloudflare), or Let's Encrypt rate limits after repeated failed attempts.

```bash
docker compose -f /opt/cookiekeeper/caddy/compose.caddy.yml --project-name cookiekeeper-caddy logs | grep -i -e acme -e certificate
```

### The API won't start

A migration failure and a bad credential look identical from outside — both leave the container unhealthy. Read the log:

```bash
dc logs --tail 200 api

# psql runs on the app host itself (postgresql-client-16 is preinstalled), against the private IP
PGPASSWORD="$(grep '^DB_PASSWORD=' .env | cut -d= -f2-)" \
  psql "postgresql://cookiekeeper@10.20.<env>.20:5432/cookiekeeper?sslmode=verify-ca&sslrootcert=/opt/cookiekeeper/<env>/pgca.crt" \
  -c 'select version, description, success from flyway_schema_history order by installed_rank desc limit 3;'
```

A `false` in `success` means a migration failed partway. Do not edit the applied migration — fix forward with a new one.

### Database connection refused

Now a two-machine problem, so work outward from the app host. Each layer below fails with a different message; read the actual error before changing anything:

```bash
# 1. Does the app host have a route at all? (private NIC up, right subnet)
ping -c1 10.20.<env>.20

# 2. Is Postgres listening and does ufw let this source in?
nc -vz 10.20.<env>.20 5432

# 3. Can psql authenticate from the host — i.e. is it credentials or containers?
PGPASSWORD=… psql "postgresql://cookiekeeper@10.20.<env>.20:5432/cookiekeeper?sslmode=verify-ca&sslrootcert=/opt/cookiekeeper/<env>/pgca.crt" -c 'select 1'

# 4. Same from inside a container — if 3 works and this does not, it is the egress firewall
dc exec api sh -c 'nc -vz 10.20.<env>.20 5432'
```

The characteristic failures, in the order you will actually hit them:

- **`no pg_hba.conf entry for host …`** — the app host is reaching Postgres from an address the database does not expect. Check `EGRESS_DB_TARGETS` and `pg_hba.conf` agree on the app host's private IP.
- **`SSL error: certificate verify failed`** — `pgca.crt` on the app host no longer matches `/etc/postgresql/ssl/server.crt` on the database host. Re-copy it (§11.1); regenerating the certificate on the database host invalidates every app host copy.
- **`password authentication failed`** — `DB_PASSWORD` in `.env` and the role password diverged. Rotate on the **server first**, then the `.env`; the reverse order leaves the app presenting a password the server was never told about.
- **Times out from a container but works from the host** — ADR-18. Run `/opt/cookiekeeper/egress-firewall.sh verify` and confirm `EGRESS_DB_TARGETS` names this environment's database.

Postgres binds only `localhost` and its private IP, and ufw only admits the matching app host. If you can reach 5432 from your laptop, that is the bug — and it is a serious one, because the Hetzner cloud firewall is structurally unable to catch it (it does not see private traffic).

### Stripe webhooks not arriving

```bash
grep STRIPE_WEBHOOK_SECRET .env         # must be THIS endpoint's secret, not another environment's
dc logs --tail 200 api | grep -i stripe
```

Then check the endpoint's delivery attempts in the Stripe dashboard. A 400 with a signature error means the wrong secret; a timeout means the request never reached the origin (DNS, Cloudflare, or the firewall).

### Email not being delivered

```bash
grep -E '^(MAIL_|BREVO_|SUPPORT_)' .env
dc logs --tail 200 api | grep -i -e brevo -e mail
```

Check which environment you are in first. **prd** is the only one that delivers to real inboxes — `MAIL_PROVIDER` must be `brevo` there, and `brevo` with a missing key stops the container at startup rather than dropping mail silently. On **dev**, mail *not* arriving in a real inbox is correct: `MAIL_PROVIDER=smtp` sends it to Mailpit, so look for it at http://localhost:8025 through the SSH tunnel (§7.6). The mistake to look for on dev is the opposite one — `brevo` set there means test email is going to real people.

### The widget isn't loading on a customer's site

```bash
curl -I https://cdn.cookiekeeper.eu/v1.js          # static file from /srv/cdn/prd
curl -I https://cdn.cookiekeeper.eu/cfg/<siteKey>.json   # proxied to the API; max-age=300
ls -la /srv/cdn/prd
```

A 404 on `/v1.js` means the CI upload never landed. A 404 on `/cfg/...` is an application answer — the site key is wrong or the banner config was never published.

### Disk filling up

Different cause on each kind of host, so check the right one.

**App host** — almost always Docker images. `deploy.sh` prunes images older than a week on every deploy; if deploys have been rare, prune by hand:

```bash
df -h /
docker system df
docker image prune -af --filter 'until=168h'
```

Never prune volumes blindly: `caddy-data` holds the TLS certificates, and losing it forces a re-issue against Let's Encrypt rate limits.

**Database host** — the candidates are the data directory, WAL that cannot be recycled, and local backup copies:

```bash
df -h /
du -sh /var/lib/postgresql/16/main /var/lib/postgresql/16/main/pg_wal /var/backups/cookiekeeper

# Biggest relations, if it is genuinely the data
runuser -u postgres -- psql -d cookiekeeper -c \
  "select relname, pg_size_pretty(pg_total_relation_size(c.oid)) as size
     from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname not in ('pg_catalog','information_schema') and c.relkind = 'r'
    order by pg_total_relation_size(c.oid) desc limit 10;"
```

Growing `pg_wal` is the one to treat as urgent: it means something is holding WAL back (an abandoned replication slot, or archiving failing), and a full disk stops Postgres writing. Old dumps under `/var/backups/cookiekeeper` are pruned by `backup.sh` at `BACKUP_LOCAL_RETENTION_DAYS`; if they have accumulated, the cron job has been failing — read `/var/log/cookiekeeper-backup.log` rather than just deleting them, because a silently broken backup is the actual problem.

The `consent_events` partitions are not garbage: retention drops them on a 3-year floor (ADR-16) and nothing else may delete them (constraint #3).

---

## 16. Support & Runbook

**Support inbox:** `support@cookiekeeper.eu` — also the destination for the in-app contact form (`SUPPORT_INBOX`). Customer replies go via the message's `Reply-To`, so a customer's address never appears in the `To` field.

**Access:** on an **app** host, SSH as `deploy` with a key for anything deployment-shaped; password auth and root password login are disabled everywhere. The GitHub Actions deploy key is that same `deploy` user, so anything CI can do you can reproduce by hand. Host-level work (firewall, cron, systemd) needs root. **Database** hosts have no `deploy` user at all — they are root-only by design, because nothing automated should ever log into them; CI has no key for them and no reason to.

**Rollback:** re-run `deploy.sh <env> <previous-tag>` on that environment's app host. Images for the last week are still local, so it is a container restart, not a rebuild. Rolling *back* across a schema migration is not automatic — Flyway does not undo. If the migration was additive (the normal case) the previous image usually still runs; if it was destructive, restore from backup instead. Note that the database is no longer part of a rollback: redeploying the app touches nothing on the database host, which is exactly why the split makes rollbacks less frightening than they used to be.

**Where things are documented:**

| Topic | File |
|-------|------|
| Architecture, ADRs, constraint rationale | `docs/ARCHITECTURE.md` |
| One-time server hardening and setup | `infra/scripts/server-setup.md` |
| Uptime monitoring design | `infra/monitoring/uptime.md` |
| Load testing | `infra/load/README.md` |
| Terraform module layout | `infra/terraform/README.md` |

---

**Last updated:** 2026-08-19
**Owner:** Yorch
**Status:** Ready for MVP deployment
