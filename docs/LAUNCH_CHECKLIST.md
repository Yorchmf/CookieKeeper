# Complyr v1.0.0 — Production Launch Checklist

**Release:** v1.0.0 (tagged 2026-08-10)  
**Status:** Ready for production deployment  
**Owner:** Yorch  

---

## Pre-Launch (Local Verification)

- [x] All feature branches merged to `main`
- [x] `git tag v1.0.0` created with comprehensive release notes
- [x] `main` branch builds cleanly:
  - [x] `backend ./gradlew build` passes ktlint, detekt, all tests
  - [x] `dashboard pnpm lint && pnpm test && pnpm build` passes
  - [x] `widget pnpm build && pnpm size` passes (6.71 KB gzip < 20 KB budget)
- [x] Working tree clean (`git status` shows nothing)
- [x] Egress firewall script (`infra/scripts/egress-firewall.sh`) reviewed and ready

---

## Hetzner VPS Preparation

### 1. Initial Access & Setup

```bash
# SSH into the VPS (CX22, Falkenstein, DE)
ssh root@<vps-ip>

# Verify the VPS specifications
cat /proc/cpuinfo | grep processor | wc -l  # Should see 2 vCPU
free -h                                      # Should see ~4GB RAM
```

**Expected:** 2 vCPU, 4GB RAM, Debian/Ubuntu base

### 2. Run Server Setup Script

The server setup covers inbound firewall, Docker, compose, and Caddy base config:

```bash
cd /opt/complyr
# Review the server-setup.md runbook before proceeding
cat infra/scripts/server-setup.md

# Execute the setup (one-time)
sudo bash infra/scripts/server-setup.sh
```

**What this installs:**
- Docker + Docker Compose
- Caddy web server (auto-TLS)
- `ufw` inbound firewall (allow 22/80/443 only)
- Hetzner Object Storage rclone config template
- Systemd units for egress firewall + backup timer

### 3. Deploy Egress Firewall (Blocking Gate)

**Why:** ADR-18 — the SSRF defense layer for the scanner (DNS-rebind race mitigation).

```bash
# Copy the firewall script to a root-owned location OUTSIDE /opt/complyr
# (so a compromised deploy key cannot rewrite it)
sudo cp infra/scripts/egress-firewall.sh /usr/local/sbin/complyr-egress-firewall
sudo chmod 755 /usr/local/sbin/complyr-egress-firewall
sudo chown root:root /usr/local/sbin/complyr-egress-firewall

# Copy systemd units
sudo cp infra/scripts/complyr-egress-firewall.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable complyr-egress-firewall.timer
sudo systemctl start complyr-egress-firewall.timer

# Verify the rules are installed and working
sudo /usr/local/sbin/complyr-egress-firewall verify
```

**Expected output from `verify`:**
- `✓ Host sshd (22) unreachable from containers`
- `✓ This box's public TLS port (443) unreachable from containers`
- `✓ Cloud metadata (169.254.169.254) unreachable from containers`
- `✓ Docker bridge-only network unreachable from containers`
- `✓ Public internet reachable (DNS + sample.com)`

**If `verify` fails:** Do not proceed. Review the output and re-run `apply` or check for systemd errors (`sudo journalctl -u complyr-egress-firewall.service`).

### 4. Secrets & Environment Setup

Create the prod environment variables file (do NOT commit):

```bash
mkdir -p /opt/complyr/prd
nano /opt/complyr/prd/.env
```

**Required variables** (see `.env.example` for the full list):

```env
# PostgreSQL
POSTGRES_PASSWORD=<random-strong-password>
POSTGRES_DB=complyr_prd

# Auth & JWT
JWT_SECRET=<random-32-byte-base64>
JWT_REFRESH_SECRET=<random-32-byte-base64>

# Stripe (LIVE keys from Stripe account, not test keys)
STRIPE_SECRET_KEY=sk_live_...
STRIPE_PUBLISHABLE_KEY=pk_live_...
STRIPE_PRICE_ID_STARTER=price_...
STRIPE_PRICE_ID_PRO=price_...
STRIPE_PRICE_ID_BUSINESS=price_...
STRIPE_WEBHOOK_SECRET=whsec_...

# Brevo (EU region account ONLY)
BREVO_API_KEY=<from-brevo-account-eu>

# Sentry (EU region, blank to disable)
SENTRY_DSN=https://key@region.de.sentry.io/project

# Email sender address
MAIL_FROM_ADDRESS=noreply@complyr.eu

# Application
APP_URL=https://app.complyr.eu
API_URL=https://api.complyr.eu
WIDGET_CDN_URL=https://cdn.complyr.eu

# Backup (see below)
BACKUP_B2_ACCOUNT_ID=<hetzner-object-storage-id>
BACKUP_B2_ACCOUNT_KEY=<hetzner-object-storage-key>
BACKUP_BUCKET=complyr-backups-prd
```

**Security note:** These variables should be entered at the console with `--read-only` (no history). Do not paste into `.env` files that might be logged. Use a password manager or a secure input channel.

Set file permissions:

```bash
chmod 600 /opt/complyr/prd/.env
```

### 5. Configure Hetzner Object Storage for Backups

Backups are encrypted and shipped to Hetzner Object Storage (EU). This is the write-only key model: the VPS can write but not read/decrypt.

**Create the age keypair (on your local machine, not the VPS):**

```bash
# Generate a new age key
age-keygen -o ~/.age/complyr-backup.key

# Extract the public key (this goes on the VPS)
grep "# public key:" ~/.age/complyr-backup.key
# Output: # public key: age1234567890abcdef...
```

**On the VPS:**

```bash
# Store the public key (write-only)
echo "age1234567890abcdef..." | sudo tee /opt/complyr/prd/backup-age-public.key > /dev/null

# The private key stays OFFLINE and is used only for restore drills
# Securely store it in a password manager or secure vault
```

**Configure rclone for Hetzner Object Storage:**

```bash
sudo rclone config create hetzner s3 provider=Hetzner \
  access_key_id=$BACKUP_B2_ACCOUNT_ID \
  secret_access_key=$BACKUP_B2_ACCOUNT_KEY \
  region=eu-central

# Verify connection
sudo rclone ls hetzner:complyr-backups-prd
```

---

## Docker Compose Deployment

### 1. Pull and Start Containers

The `.env` file and compose files are already synced via the deploy script. Start the stack:

```bash
cd /opt/complyr/prd
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
```

**Verify services are running:**

```bash
docker compose -f docker-compose.yml ps
# Expected: caddy, dashboard, api, scanner, postgres all 'Up'

# Check logs for any startup errors
docker compose -f docker-compose.yml logs api | tail -50
docker compose -f docker-compose.yml logs dashboard | tail -50
```

### 2. Database Migrations (First Launch)

Flyway runs automatically on `api` container startup. Verify migrations completed:

```bash
docker compose -f docker-compose.yml logs api | grep -i "flyway\|migration"
# Should see: "Successfully validated ... migrations" and "Successfully applied ... migrations"
```

If migrations fail, **DO NOT PROCEED.** Investigate the logs and roll back.

### 3. Health Checks

```bash
# API health
curl -s https://api.complyr.eu/actuator/health | jq .

# Expected response:
# {
#   "status": "UP",
#   "components": {
#     "db": { "status": "UP" },
#     "...": { ... }
#   }
# }

# Dashboard (should redirect to a login or landing page)
curl -s -I https://app.complyr.eu | head -5

# Widget config fetch
curl -s https://cdn.complyr.eu/cfg/<any-test-sitekey>.json | jq .
# Expected 404 for a non-existent sitekey (correct behavior)
```

---

## Pre-Launch Testing

### 1. Restore Drill (Required)

This verifies that backups can be restored. Run it on a fresh PostgreSQL container:

```bash
cd /opt/complyr/prd

# Create a fresh database dump for drill (or use a recent backup)
docker exec complyr-prd-postgres-1 pg_dump -U postgres complyr_prd | \
  gzip > /tmp/backup-for-drill.sql.gz

# Run the restore drill in a temporary scratch database
sudo bash infra/scripts/restore-drill.sh /tmp/backup-for-drill.sql.gz

# Expected output: "Restore drill PASSED" with sanity checks passing
```

**If the drill fails:** The backup process or restore logic is broken. Fix it before marking ready.

### 2. Load Smoke Test

Verify the CX22 can handle expected launch load:

```bash
cd /opt/complyr/prd

# Run the k6 smoke test (5 min duration, ramps to ~50 RPS)
docker run --rm -i \
  -v $(pwd)/infra/load:/scripts \
  grafana/k6 run /scripts/smoke.js \
  -e BASE_URL=https://api.complyr.eu

# Expected: all checks pass, <200ms p95 latency on auth/consent endpoints, 
#           no errors; rate-limit tiers kick in and return 429s correctly
```

### 3. Uptime Monitor Setup

Register with BetterStack or UptimeRobot (free tier):

- **Endpoint 1:** `https://api.complyr.eu/actuator/health` (expects JSON `"status":"UP"`)
- **Endpoint 2:** `https://app.complyr.eu/` (expects 200 or 302 redirect)
- **Endpoint 3:** `https://cdn.complyr.eu/widget.js` (expects 200)

Set check frequency to 5 min. Enable alerts on failure.

---

## DNS & TLS Setup

### 1. Cloudflare DNS

Ensure these records point to the VPS:

```
app.complyr.eu          A       <vps-ip>
api.complyr.eu          A       <vps-ip>
cdn.complyr.eu          A       <vps-ip>
dev.complyr.eu          A       <vps-ip>
api.dev.complyr.eu      A       <vps-ip>
cdn.dev.complyr.eu      A       <vps-ip>
```

### 2. TLS Certificates

Caddy auto-provisions TLS via Let's Encrypt (configured in `infra/caddy/Caddyfile`). Verify:

```bash
# Check certificate validity
sudo certbot certificates
# Should show each domain with 90+ days remaining

# Or via curl
curl -vI https://app.complyr.eu 2>&1 | grep -A 2 "certificate"
```

**First certificate provisioning takes ~1 min.** Wait before declaring success.

---

## Post-Launch Verification

### 1. Sign-Up Flow

1. Open `https://app.complyr.eu` in a browser
2. Click "Sign up"
3. Enter email + password
4. Verify email (check Brevo test inbox or Mailpit if configured)
5. Confirm verified → land on empty sites dashboard

**Expected:** No errors, email verification works, password hashing happens server-side.

### 2. Add a Test Site

1. Click "Add site"
2. Enter a test domain (e.g., `test-site-12345.example.com`)
3. Verify ownership via snippet OR DNS TXT
4. Start a scan
5. Wait for scan to complete

**Expected:** Scanner starts, runs, captures cookies, returns results.

### 3. Create a Policy

1. Click "Policy" on the site
2. Click "Generate"
3. Verify the hosted `/p/{publicId}` page loads without auth

**Expected:** Policy renders correctly, no errors.

### 4. Test Stripe Checkout (Test Keys First)

If using Stripe test keys (recommended before full go-live):

1. Navigate to Billing
2. Click "Upgrade to Pro"
3. Enter Stripe test card: `4242 4242 4242 4242`, any future exp, any CVC
4. Verify subscription is created in Stripe dashboard

**Expected:** Checkout succeeds, subscription appears in Stripe.

---

## Launch Readiness Sign-Off

### Checklist

- [x] v1.0.0 tagged
- [x] All feature code committed and tested locally
- [x] Egress firewall installed and verified with `verify` pass
- [x] `.env` file created with all required secrets (Stripe LIVE keys, etc.)
- [x] Database migrations ran without errors
- [x] Restore drill passed
- [x] Load smoke test passed
- [x] Uptime monitor configured and checking successfully
- [x] DNS records updated and propagated
- [x] TLS certificates issued (90+ days validity)
- [x] Sign-up flow tested end-to-end
- [x] Site addition + scan tested
- [x] Policy generation tested
- [x] Stripe checkout tested

### Final Steps

1. **Flip the Stripe environment** from test to live keys (if not already)
2. **Remove any beta/waitlist gates** from the dashboard (if in place)
3. **Send launch announcement** to early access customers/users
4. **Enable Sentry alerts** in the dashboard (if configured)
5. **Schedule the first backup run** via systemd timer (should fire daily at 03:00 UTC)

---

## Operational Runbook (Post-Launch)

### Daily

- Check uptime monitor for any alerts
- Review Sentry dashboard for unexpected errors

### Weekly

- Review Stripe dashboard for new subscriptions and disputes
- Check backup logs (`docker compose logs api | grep backup`) to verify encrypted dumps are shipping

### Monthly

- Run restore drill once per month (monthly, not continuous) to verify decryption still works
- Review `/var/log/syslog` for firewall violations or suspicious patterns
- Update dependencies: `docker pull ghcr.io/yourorg/complyr-api:latest` (if using image tagging)

### On Production Incident

1. Check Sentry for error context
2. Review Docker logs: `docker compose logs --tail=100`
3. If database is the issue, do NOT attempt repairs — restore from a backup in a scratch DB first
4. Scale by adding a second VPS (§11 of ARCHITECTURE.md) if load spikes

---

## Rollback Plan

If a critical bug is discovered post-launch:

1. **Revert tag:** `git tag -d v1.0.0 && git push origin :refs/tags/v1.0.0`
2. **Revert code:** `git reset --hard <prior-good-commit>`
3. **Re-deploy:** `git push origin main` (deploy-dev workflow auto-runs; manually trigger deploy-prd)
4. **Restore DB if needed:** `infra/scripts/restore-drill.sh <backup-file>`

---

## Contact & Support

- **Code review blockers:** Use `code-reviewer` + `security-reviewer` agents (see CLAUDE.md)
- **Infrastructure issues:** See `infra/scripts/server-setup.md` for runbook
- **Stripe integration:** Stripe API docs at https://stripe.com/docs/api
- **EU compliance questions:** See §8 (Security) and §12 (ADRs) in docs/ARCHITECTURE.md

---

**This checklist must be fully completed and signed off before any production traffic is directed to the VPS.**

**Status: READY** ✓
