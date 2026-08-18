# CookieKeeper — Deployment & Operations Guide

Complete setup instructions for all third-party services, environment configurations, and infrastructure.

---

## 1. Overview

CookieKeeper runs on a **single Hetzner CX22 VPS** in Germany with two isolated environments (dev + prd) via Docker Compose. All customer data, backups, and infrastructure stay within the EU.

```
Cloudflare (DNS, CDN, WAF, TLS edge)
    ↓
Hetzner VPS (CX22, 2vCPU/4GB, Falkenstein, Germany)
    ├─ Caddy (reverse proxy, auto-TLS, routing)
    ├─ dev environment (compose project complyr-dev)
    │   ├─ api (Spring Boot, profile=api)
    │   ├─ dashboard (Next.js)
    │   ├─ scanner (Spring Boot, profile=scanner)
    │   └─ postgres (database + job queue)
    ├─ prd environment (compose project complyr-prd)
    │   ├─ api (Spring Boot, profile=api)
    │   ├─ dashboard (Next.js)
    │   ├─ scanner (Spring Boot, profile=scanner)
    │   └─ postgres (database + job queue)
    └─ backups (pg_dump encrypted to Hetzner Object Storage)

Stripe (billing webhooks)
Brevo (transactional email)
GitHub (source, Actions CI/CD, GHCR image registry)
```

---

## 2. Prerequisites & Accounts

Create accounts **in this order** before provisioning infrastructure:

| Service | Purpose | Cost | Account Type |
|---------|---------|------|--------------|
| **GitHub** | Source repo, Actions CI, GHCR registry | Free | Personal or Org |
| **Hetzner** | VPS + Object Storage backups | €3.79/mo + storage | Dedicated account |
| **Cloudflare** | DNS, CDN, WAF, edge caching | Free | Dedicated account |
| **Stripe** | Billing, checkout, subscriptions, EU VAT | Per-transaction | Merchant account |
| **Brevo** | Transactional email (300/day free) | Free tier | EU region account |

---

## 3. Hetzner VPS Setup (CX22)

### 3.1 Create the VPS

**Portal:** https://console.hetzner.cloud

1. **Create project:** `cookiekeeper`
2. **Add server:**
   - Image: **Ubuntu 24.04 LTS**
   - Type: **CX22** (2 vCPU, 4GB RAM, 40GB SSD)
   - Location: **Falkenstein, Germany** (EU residency requirement)
   - SSH key: Upload your public key (not password auth)
   - Name: `cookiekeeper-vps` or `api.cookiekeeper.eu`

3. **After boot**, note the IP address and add to your SSH config:

```bash
# ~/.ssh/config
Host cookiekeeper
    HostName <IP>
    User root
    IdentityFile ~/.ssh/id_rsa
```

### 3.2 Secure the VPS

SSH in and run the hardening script:

```bash
ssh root@cookiekeeper

# Update system
apt update && apt upgrade -y

# Install essentials
apt install -y \
  curl wget git docker.io docker-compose-plugin \
  ufw fail2ban htop net-tools jq age \
  postgresql-client sqlite3 gpg

# Create deploy user (no sudo, no shell)
useradd -m -s /usr/sbin/nologin -G docker deploy

# SSH for deploy user (CI/CD will use this key)
mkdir -p /home/deploy/.ssh
chmod 700 /home/deploy/.ssh
# (paste deploy key public part here)
chown -R deploy:deploy /home/deploy/.ssh
chmod 600 /home/deploy/.ssh/authorized_keys

# Firewall rules (allow SSH, HTTP, HTTPS only)
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp comment "SSH"
ufw allow 80/tcp comment "HTTP"
ufw allow 443/tcp comment "HTTPS"
ufw enable

# Fail2ban for brute-force protection
systemctl enable fail2ban
systemctl start fail2ban

# Docker daemon (pinned public DNS, no IPv6, no default-address-pools)
cat > /etc/docker/daemon.json <<'EOF'
{
  "dns": ["8.8.8.8", "1.1.1.1"],
  "ipv6": false,
  "userland-proxy": false,
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF

systemctl restart docker

# Sysctl for iptables (required for container egress firewall)
sysctl -w net.bridge.bridge-nf-call-iptables=1
echo "net.bridge.bridge-nf-call-iptables=1" >> /etc/sysctl.conf

# Disable IPv6 in JVM (Hetzner DNS rebind mitigation)
echo "-Dsun.net.inetaddr.ttl=30" >> /etc/environment

# Create deploy directories
mkdir -p /opt/cookiekeeper/{dev,prd}
mkdir -p /opt/cookiekeeper/backups
chown -R deploy:deploy /opt/cookiekeeper
chmod 750 /opt/cookiekeeper/{dev,prd}

echo "VPS hardened and ready."
```

### 3.3 Hetzner Object Storage Setup

**Purpose:** Encrypted backup storage (pg_dump → gzip → age → Object Storage)

1. **Enable Object Storage** in Hetzner Console
2. **Create bucket:** `cookiekeeper-backups` in **Falkenstein (Europe)** region
3. **Create API token:** S3-compatible, note the credentials

```bash
# On VPS, install rclone
apt install -y rclone

# Configure rclone for Hetzner S3
rclone config create hetzner s3 \
  --s3-access-key-id "<ACCESS_KEY>" \
  --s3-secret-access-key "<SECRET_KEY>" \
  --s3-endpoint "https://fsn1.your-objectstorage.com" \
  --s3-region "fsn1" \
  --s3-acl "private"
```

---

## 4. Cloudflare DNS Setup

**Purpose:** DNS, CDN edge caching, TLS at edge, WAF, rate limiting

### 4.1 Domain & Nameservers

1. **Add domain to Cloudflare:** https://dash.cloudflare.com/
2. **Update domain registrar** to use Cloudflare nameservers:
   - `colt.ns.cloudflare.com`
   - `talia.ns.cloudflare.com`
3. **Wait for propagation** (15–30 minutes)

### 4.2 DNS Records

Create these **A records** pointing to your Hetzner VPS IP:

```
CNAME records (dev environment):
dev.cookiekeeper.eu          CNAME  cookiekeeper.eu
api.dev.cookiekeeper.eu      CNAME  cookiekeeper.eu
cdn.dev.cookiekeeper.eu      CNAME  cookiekeeper.eu

CNAME records (prod environment):
app.cookiekeeper.eu          CNAME  cookiekeeper.eu
api.cookiekeeper.eu          CNAME  cookiekeeper.eu
cdn.cookiekeeper.eu          CNAME  cookiekeeper.eu

Root A record (Hetzner VPS IP):
cookiekeeper.eu              A      <HETZNER_IP>
```

### 4.3 Cloudflare Settings

**SSL/TLS:**
- Mode: **Full (Strict)** — origin certificate pinning
- Generate an origin certificate in Cloudflare, download, and place on VPS at `/opt/cookiekeeper/ssl/`

**Caching Rules:**
```
Path: /cfg/*
Cache TTL: 5 minutes (widget config is dynamic, don't cache long)

Path: /p/*
Cache TTL: 1 hour (policy pages are versioned, safe to cache)

Path: /api/v1/consent
Cache: Bypass (must never cache consent POST)
```

**Rate Limiting (WAF Rules):**
```
Rule: Block if request count > 100 in 10 minutes
Applies to: /api/v1/auth/login, /api/v1/consent, /api/v1/widget-config
Action: Block (429)
```

---

## 5. Caddy Reverse Proxy Setup

**What is Caddy?**

Caddy is a lightweight HTTP/2 reverse proxy that:
- Routes requests by hostname to the right container (dashboard, API, scanner)
- Handles TLS termination (auto-renews, pinned to Cloudflare origin cert)
- Serves static assets (widget config cache, public policy pages)
- Logs requests (JSON format to stdout, sent to Docker logs)

### 5.1 Caddy Configuration

Place at `/opt/cookiekeeper/Caddyfile`:

```caddyfile
# Global settings
{
  email support@cookiekeeper.eu
  default_sns_sni cookiekeeper.eu
  
  # Use Cloudflare origin certificate for TLS
  tls /opt/cookiekeeper/ssl/cert.pem /opt/cookiekeeper/ssl/key.pem
}

# === DEV ENVIRONMENT ===

dev.cookiekeeper.eu:443 {
  encode gzip
  
  # Dashboard (Next.js container)
  @dashboard path /dashboard /account /sites /scan/* /settings*
  handle @dashboard {
    reverse_proxy dashboard-dev:3000
  }
  
  # API (Spring Boot, profile=api)
  @api path /api/v1*
  handle @api {
    reverse_proxy api-dev:8080
  }
  
  # Widget config CDN (cached by Cloudflare, 5 min TTL)
  @widget_cfg path /cfg/*
  handle @widget_cfg {
    header Cache-Control "public, max-age=300"
    reverse_proxy api-dev:8080
  }
  
  # Health check
  @health path /actuator/health
  handle @health {
    reverse_proxy api-dev:8080
  }
  
  # Fallback to dashboard (SPA routing)
  handle {
    reverse_proxy dashboard-dev:3000
  }
}

api.dev.cookiekeeper.eu:443 {
  reverse_proxy api-dev:8080
}

cdn.dev.cookiekeeper.eu:443 {
  header Cache-Control "public, max-age=300"
  reverse_proxy api-dev:8080
}

# === PRODUCTION ENVIRONMENT ===

app.cookiekeeper.eu:443 {
  encode gzip
  
  @dashboard path /dashboard /account /sites /scan/* /settings*
  handle @dashboard {
    reverse_proxy dashboard-prd:3000
  }
  
  @api path /api/v1*
  handle @api {
    reverse_proxy api-prd:8080
  }
  
  @widget_cfg path /cfg/*
  handle @widget_cfg {
    header Cache-Control "public, max-age=300"
    reverse_proxy api-prd:8080
  }
  
  @health path /actuator/health
  handle @health {
    reverse_proxy api-prd:8080
  }
  
  handle {
    reverse_proxy dashboard-prd:3000
  }
}

api.cookiekeeper.eu:443 {
  reverse_proxy api-prd:8080
}

cdn.cookiekeeper.eu:443 {
  header Cache-Control "public, max-age=300"
  reverse_proxy api-prd:8080
}

# HTTP → HTTPS redirect
:80 {
  redir https://{host}{uri} permanent
}
```

### 5.2 Caddy in Docker Compose

Caddy runs in both dev and prd compose stacks:

```yaml
services:
  caddy:
    image: caddy:latest
    container_name: caddy
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - /opt/cookiekeeper/Caddyfile:/etc/caddy/Caddyfile:ro
      - /opt/cookiekeeper/ssl:/etc/caddy/ssl:ro
      - caddy-data:/data
      - caddy-config:/config
    networks:
      - caddy-net
    restart: unless-stopped
    logging:
      driver: "json-file"
      options:
        max-size: "10m"
        max-file: "3"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost/health"]
      interval: 10s
      timeout: 5s
      retries: 3

networks:
  caddy-net:
    driver: bridge
    ipam:
      config:
        - subnet: 10.31.30.0/24

volumes:
  caddy-data:
  caddy-config:
```

---

## 6. Stripe Setup (Billing)

### 6.1 Create Merchant Account

1. Go to **https://dashboard.stripe.com**
2. **Activate account** with business details
3. **Enable EU VAT collection** via Stripe Tax (€0.50/tx accepted)
4. **Set up bank account** for payouts (SEPA, EU bank preferred)

### 6.2 API Keys

**Dashboard → Developers → API keys**

Generate:
- **Publishable key** (frontend, public) — starts with `pk_`
- **Secret key** (backend only, private) — starts with `sk_`

For **dev and prd** environments, create separate keys by creating two separate Stripe accounts or using "Restricted Keys" — one for test mode, one for live mode.

### 6.3 Webhook Endpoint

**Dashboard → Developers → Webhooks**

1. **Add endpoint:** `https://api.cookiekeeper.eu/api/v1/webhooks/stripe`
2. **Select events to listen:**
   - `customer.subscription.created`
   - `customer.subscription.updated`
   - `customer.subscription.deleted`
   - `invoice.payment_succeeded`
   - `invoice.payment_failed`
3. Note the **signing secret** (starts with `whsec_`)

### 6.4 Environment Variables

```bash
# .env files for dev and prd

# Stripe (dev = test mode keys, prd = live keys)
STRIPE_PUBLIC_KEY=pk_test_... (dev) or pk_live_... (prd)
STRIPE_SECRET_KEY=sk_test_... (dev) or sk_live_... (prd)
STRIPE_WEBHOOK_SECRET=whsec_test_... (dev) or whsec_live_... (prd)
STRIPE_WEBHOOK_TOLERANCE_SECONDS=300
```

### 6.5 Stripe Tax Configuration

**Dashboard → Settings → Stripe Tax**

- Enable automatic tax calculation
- Set business address (Germany for EU residency)
- Configure VAT rates (EU 17–27% depending on customer location)

---

## 7. Brevo Email Setup (Transactional Email)

**Why Brevo?** EU-based (France), generous free tier (300 emails/day), simple HTTP API, no external dependency in code.

### 7.1 Create Account

1. Go to **https://www.brevo.com**
2. **Create account** with business email
3. **IMPORTANT:** During signup, select **EU region** (not US)
4. **Verify sender email** (the `From:` address for transactional mail)

### 7.2 SMTP Setup (Alternative to API)

**Brevo offers both HTTP API and SMTP.** The app uses **HTTP API** (`BrevoEmailSender`), but SMTP is available for local/manual testing:

**SMTP Credentials:**
- Host: `smtp-relay.brevo.com`
- Port: `587` (TLS)
- Username: Your Brevo login email
- Password: Your SMTP password (generated in **Brevo dashboard → SMTP & API → SMTP**)

### 7.3 API Key

**Brevo dashboard → SMTP & API → API keys**

1. **Create API key** with "Full access"
2. Note the **v3 API key** (starts with `xkeysib-`)

### 7.4 Approved Senders

**SMTP & API → Senders**

Add at least:
- `noreply@cookiekeeper.eu` — for transactional emails (verification, receipts)
- `support@cookiekeeper.eu` — for customer support emails

Each sender must be verified (click link in verification email).

### 7.5 Environment Variables

```bash
# .env files for dev and prd

# Brevo
BREVO_API_KEY=xkeysib-... (same for dev and prd, API key is region-agnostic)
BREVO_SENDER_EMAIL=noreply@cookiekeeper.eu
BREVO_SENDER_NAME=CookieKeeper
BREVO_WEBHOOK_SECRET=(optional, for bounces/complaints)

# Email provider selection
MAIL_PROVIDER=brevo (can switch to "smtp" for local testing with Mailpit)
```

### 7.6 Local Testing (Mailpit)

For local development, use **Mailpit** (Docker container that captures SMTP mail):

```yaml
# In compose.local.yml
services:
  mailpit:
    image: axllent/mailpit:latest
    ports:
      - "1025:1025" # SMTP
      - "8025:8025" # Web UI (http://localhost:8025)
    environment:
      - MAILPIT_SMTP_AUTH_ACCEPT_ANY=true
    restart: unless-stopped
```

Set `MAIL_PROVIDER=smtp` and `MAIL_SMTP_HOST=mailpit:1025` in local `.env.local`.

---

## 8. GitHub Setup (CI/CD & Registry)

### 8.1 Repository

1. Create repo on **https://github.com** (public or private, recommend private for MVP)
2. Protect **main** branch:
   - **Settings → Branches → Add rule**
   - Require status checks to pass (backend build, dashboard build, widget build)
   - Require code review before merging (recommend)

### 8.2 GitHub Container Registry (GHCR)

**Why GHCR?** Free, GitHub-native, no separate account needed.

1. **Create Personal Access Token:**
   - **Settings → Developer settings → Personal access tokens → Tokens (classic)**
   - **Generate new token (classic)**
   - Scopes: `repo`, `write:packages`, `read:packages`
   - Save the token (you'll never see it again)

2. **Docker login** (on VPS):

```bash
echo $GITHUB_TOKEN | docker login ghcr.io -u <YOUR_USERNAME> --password-stdin
```

3. **Images built by Actions are pushed to:**
   - `ghcr.io/your-org/cookiekeeper-api:latest`
   - `ghcr.io/your-org/cookiekeeper-dashboard:latest`
   - `ghcr.io/your-org/cookiekeeper-scanner:latest` (same image as api, diff profile)

### 8.3 Deploy Key for VPS

1. **Generate SSH key on VPS:**

```bash
ssh-keygen -t ed25519 -f /home/deploy/.ssh/deploy_key -N ""
cat /home/deploy/.ssh/deploy_key.pub
```

2. **Add to GitHub:**
   - **Settings → Deploy keys → Add deploy key**
   - Paste the public key
   - Check "Allow write access"

3. **Add private key as GitHub Actions secret:**
   - **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `DEPLOY_KEY`
   - Value: (paste private key from `/home/deploy/.ssh/deploy_key`)

### 8.4 CI/CD Workflow (`.github/workflows/deploy.yml`)

Example workflow file (already in the repo):

```yaml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      packages: write
    steps:
      - uses: actions/checkout@v4
      
      - name: Build backend
        run: cd backend && ./gradlew build -x test
      
      - name: Build dashboard
        run: cd dashboard && pnpm install && pnpm build
      
      - name: Build widget
        run: cd widget && pnpm install && pnpm build
      
      - name: Push images to GHCR
        run: |
          echo ${{ secrets.GITHUB_TOKEN }} | docker login ghcr.io -u ${{ github.actor }} --password-stdin
          docker build -t ghcr.io/${{ github.repository_owner }}/cookiekeeper-api:latest ./backend
          docker push ghcr.io/${{ github.repository_owner }}/cookiekeeper-api:latest

  deploy-dev:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Deploy to dev
        uses: appleboy/ssh-action@master
        with:
          host: ${{ secrets.DEPLOY_HOST }}
          username: deploy
          key: ${{ secrets.DEPLOY_KEY }}
          script: |
            cd /opt/cookiekeeper/dev
            docker compose pull
            docker compose up -d
            docker compose exec -T api ./gradlew flywayMigrate
```

---

## 9. Environment Variables (.env)

### 9.1 Dev Environment (`/opt/cookiekeeper/dev/.env`)

```bash
# Server
SPRING_PROFILES_ACTIVE=api
SERVER_PORT=8080

# Database (internal docker network)
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-dev:5432/cookiekeeper_dev
SPRING_DATASOURCE_USERNAME=cookiekeeper
SPRING_DATASOURCE_PASSWORD=<STRONG_PASSWORD>
SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.PostgreSQLDialect

# JWT
COOKIEKEEPER_AUTH_JWT_SECRET=<32_CHAR_RANDOM_STRING>
COOKIEKEEPER_AUTH_JWT_EXPIRY_MINUTES=15

# Stripe (test mode keys)
STRIPE_PUBLIC_KEY=pk_test_...
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_test_...

# Brevo
BREVO_API_KEY=xkeysib-...
BREVO_SENDER_EMAIL=noreply@cookiekeeper.eu
MAIL_PROVIDER=brevo

# Sentry (optional, can leave blank for local)
SENTRY_DSN=

# App
COOKIEKEEPER_APP_BASE_URL=https://dev.cookiekeeper.eu
COOKIEKEEPER_API_BASE_URL=https://api.dev.cookiekeeper.eu
COOKIEKEEPER_CDN_BASE_URL=https://cdn.dev.cookiekeeper.eu
COOKIEKEEPER_SUPPORT_EMAIL=support@cookiekeeper.eu

# Rate limiting
COOKIEKEEPER_RATE_LIMIT_AUTH_PER_MINUTE=10
COOKIEKEEPER_RATE_LIMIT_PUBLIC_SCAN_PER_MINUTE=20
COOKIEKEEPER_RATE_LIMIT_CONSENT_PER_MINUTE=100

# Consent retention
COOKIEKEEPER_CONSENT_RETENTION_MONTHS=36
COOKIEKEEPER_CONSENT_PARTITION_LOOKAHEAD_MONTHS=3

# Logging
LOGGING_LEVEL_ROOT=INFO
LOGGING_LEVEL_EU_COOKIEKEEPER=DEBUG
```

### 9.2 Production Environment (`/opt/cookiekeeper/prd/.env`)

```bash
# Same as dev, but:

# Stripe (live mode keys)
STRIPE_PUBLIC_KEY=pk_live_...
STRIPE_SECRET_KEY=sk_live_...
STRIPE_WEBHOOK_SECRET=whsec_live_...

# Sentry (live region endpoint)
SENTRY_DSN=https://<KEY>@<ORGANIZATION_ID>.de.sentry.io/...

# URLs
COOKIEKEEPER_APP_BASE_URL=https://app.cookiekeeper.eu
COOKIEKEEPER_API_BASE_URL=https://api.cookiekeeper.eu
COOKIEKEEPER_CDN_BASE_URL=https://cdn.cookiekeeper.eu

# Stricter limits
COOKIEKEEPER_RATE_LIMIT_AUTH_PER_MINUTE=20
COOKIEKEEPER_RATE_LIMIT_PUBLIC_SCAN_PER_MINUTE=50
COOKIEKEEPER_RATE_LIMIT_CONSENT_PER_MINUTE=500

# Logging
LOGGING_LEVEL_ROOT=WARN
LOGGING_LEVEL_EU_COOKIEKEEPER=INFO
```

### 9.3 How to Apply

1. **SSH to VPS:**
   ```bash
   scp .env.dev deploy@cookiekeeper:/opt/cookiekeeper/dev/.env
   scp .env.prd deploy@cookiekeeper:/opt/cookiekeeper/prd/.env
   ```

2. **Secure permissions:**
   ```bash
   ssh deploy@cookiekeeper
   chmod 600 /opt/cookiekeeper/dev/.env /opt/cookiekeeper/prd/.env
   ```

3. **Load on container startup** (Docker Compose `env_file` directive):
   ```yaml
   services:
     api:
       env_file:
         - /opt/cookiekeeper/dev/.env
   ```

---

## 10. Docker Compose Stacks

### 10.1 Dev Stack (`/opt/cookiekeeper/dev/docker-compose.yml`)

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    container_name: postgres-dev
    environment:
      POSTGRES_DB: cookiekeeper_dev
      POSTGRES_USER: cookiekeeper
      POSTGRES_PASSWORD: <PASSWORD>
    volumes:
      - postgres-data:/var/lib/postgresql/data
    networks:
      - app-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U cookiekeeper"]
      interval: 10s
      timeout: 5s
      retries: 5

  api:
    image: ghcr.io/your-org/cookiekeeper-api:latest
    container_name: api-dev
    env_file: .env
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080"
    networks:
      - app-net
      - caddy-net
    restart: unless-stopped

  dashboard:
    image: ghcr.io/your-org/cookiekeeper-dashboard:latest
    container_name: dashboard-dev
    env_file: .env
    ports:
      - "3000"
    networks:
      - app-net
      - caddy-net
    restart: unless-stopped

  scanner:
    image: ghcr.io/your-org/cookiekeeper-api:latest
    container_name: scanner-dev
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: scanner
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - app-net
    restart: unless-stopped

networks:
  app-net:
    driver: bridge
  caddy-net:
    external: true

volumes:
  postgres-data:
```

### 10.2 Production Stack (same, but suffix `-prd`)

---

## 11. Database Initialization

### 11.1 First-Time Setup

```bash
ssh deploy@cookiekeeper

# SSH into the API container
docker -c /opt/cookiekeeper/dev exec api bash

# Run Flyway migrations (auto-run on boot, but can trigger manually)
cd /opt/cookiekeeper/dev && docker compose exec api java -jar /app/app.jar --spring.profiles.active=api

# Or via curl (the app runs migrations on startup)
curl http://localhost:8080/actuator/health
```

**Flyway automatically:**
1. Creates all tables from `src/main/resources/db/migration/V*.sql`
2. Seeds the cookie signature database (Open Cookie Database)
3. Sets up partitions for `consent_events`

### 11.2 Backups

Create a backup script at `/opt/cookiekeeper/backups/backup.sh`:

```bash
#!/bin/bash
set -e

ENV=$1  # dev or prd
DB_NAME=cookiekeeper_${ENV}
DB_USER=cookiekeeper
BACKUP_DIR=/opt/cookiekeeper/backups
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# Dump database
docker -c /opt/cookiekeeper/${ENV} exec postgres pg_dump \
  -U ${DB_USER} ${DB_NAME} | gzip > ${BACKUP_DIR}/${DB_NAME}_${TIMESTAMP}.sql.gz

# Encrypt with age (public key, write-only)
age -R /opt/cookiekeeper/backups/.age-public-keys \
  ${BACKUP_DIR}/${DB_NAME}_${TIMESTAMP}.sql.gz \
  > ${BACKUP_DIR}/${DB_NAME}_${TIMESTAMP}.sql.gz.age

# Remove unencrypted backup
rm ${BACKUP_DIR}/${DB_NAME}_${TIMESTAMP}.sql.gz

# Upload to Hetzner Object Storage
rclone copy ${BACKUP_DIR}/${DB_NAME}_${TIMESTAMP}.sql.gz.age hetzner:cookiekeeper-backups/

# Keep only last 14 days locally
find ${BACKUP_DIR} -name "*.sql.gz.age" -mtime +14 -delete

echo "Backup complete: ${DB_NAME}_${TIMESTAMP}.sql.gz.age"
```

Schedule with cron:
```bash
# Run daily at 3 AM
0 3 * * * /opt/cookiekeeper/backups/backup.sh prd >> /opt/cookiekeeper/backups/backup.log 2>&1
```

---

## 12. Caddy & CDN Hosting Explained

### 12.1 How Caddy Routes Requests

```
Customer Browser
    ↓ (HTTPS)
Cloudflare CDN (caches if Cache-Control allows)
    ↓
Hetzner VPS IP
    ↓
Caddy (listens on :443, routes by Host header)
    ├─ cdn.cookiekeeper.eu/cfg/* → API container (cached by Cloudflare, 5 min TTL)
    ├─ cdn.cookiekeeper.eu/p/* → API container (cached by Cloudflare, 1 hour TTL)
    ├─ api.cookiekeeper.eu/* → API container
    └─ app.cookiekeeper.eu/* → Dashboard container (SPA)
```

### 12.2 Widget Config CDN Flow

1. **Customer's website loads the widget:**
   ```html
   <script src="https://cdn.cookiekeeper.eu/v1.js" data-cookiekeeper="pk_live_123"></script>
   ```

2. **Widget immediately fetches config (cached):**
   ```
   GET https://cdn.cookiekeeper.eu/cfg/pk_live_123.json
   (served by Cloudflare edge, revalidates every 5 minutes)
   ```

3. **Request path:**
   - Caddy sees `cdn.cookiekeeper.eu/cfg/pk_live_123.json`
   - Routes to Spring Boot `WidgetConfigCdnController`
   - Controller returns JSON with `Cache-Control: public, max-age=300`
   - Cloudflare caches it at the edge for 5 minutes
   - Next visitor gets it from Cloudflare's edge (no app hit)

4. **Why Cloudflare?**
   - Widget is loaded on every visitor's page
   - Config changes only occasionally (customer customization)
   - Edge caching = less load on app, faster widget

### 12.3 Hosted Policy Pages (`/p/{publicId}`)

1. **Customer's site links to their policy:**
   ```html
   <a href="https://app.cookiekeeper.eu/p/pub_xyz">Cookie Policy</a>
   ```

2. **Request path:**
   - Caddy routes `/p/*` to Dashboard
   - Dashboard server-renders the policy (queries Postgres for versioned content)
   - Response includes `Cache-Control: public, max-age=3600`
   - Cloudflare caches for 1 hour

### 12.4 TLS & Origin Certificates

**Why "Full (Strict)" mode?**

Cloudflare doesn't trust your origin by default (Flexible = Cloudflare→Origin unencrypted). Full Strict means:
1. Customer → Cloudflare: encrypted (TLS)
2. Cloudflare → Hetzner VPS: encrypted (TLS with origin certificate)

**To set up:**

1. **Generate origin certificate in Cloudflare dashboard:**
   - **SSL/TLS → Origin Server → Create certificate**
   - Generated cert is valid for 15 years
   - Download and place on VPS:
     ```bash
     scp cert.pem deploy@cookiekeeper:/opt/cookiekeeper/ssl/cert.pem
     scp key.pem deploy@cookiekeeper:/opt/cookiekeeper/ssl/key.pem
     ```

2. **Caddy uses them** (configured in `Caddyfile`):
   ```caddyfile
   tls /opt/cookiekeeper/ssl/cert.pem /opt/cookiekeeper/ssl/key.pem
   ```

---

## 13. Checklist Before Launch

### 13.1 Infrastructure

- [ ] Hetzner VPS running, hardened, Docker installed
- [ ] Hetzner Object Storage bucket created (Falkenstein region)
- [ ] Cloudflare domains configured, nameservers updated
- [ ] Cloudflare origin certificate generated and on VPS

### 13.2 Third-Party Accounts

- [ ] Stripe merchant account (live mode keys ready)
- [ ] Stripe webhook endpoint configured
- [ ] Stripe Tax enabled
- [ ] Brevo account (EU region), senders verified
- [ ] Brevo API key created
- [ ] GitHub repository created, deploy key added
- [ ] GitHub Actions secrets configured (GITHUB_TOKEN, DEPLOY_KEY)

### 13.3 Configuration

- [ ] `.env.dev` and `.env.prd` files created and placed on VPS
- [ ] JWT secret generated (32+ random characters)
- [ ] Database passwords set (strong, unique)
- [ ] Caddy configuration tested locally, deployed to VPS

### 13.4 Backups

- [ ] Backup script written and tested
- [ ] Cron job scheduled for daily backups
- [ ] Restore drill run successfully

### 13.5 Monitoring

- [ ] Sentry account created (EU region), DSN configured
- [ ] Uptime monitoring set up (UptimeRobot or BetterStack)
- [ ] Log aggregation ready (Loki later, Docker logs for MVP)

### 13.6 Security

- [ ] Container egress firewall deployed and verified
- [ ] Rate limiting configured in Cloudflare + app
- [ ] SSH key-only auth on VPS (no passwords)
- [ ] Fail2ban enabled and monitoring logs
- [ ] Backup encryption keys stored safely offline

---

## 14. Scaling Path

Once live and stable:

1. **Second VPS (if >50 customers):** Split prd onto its own box, keep dev on the original
2. **Managed Postgres (if revenue grows):** Hetzner Database or AWS RDS EU (but breaks single-provider story)
3. **Cloudflare Workers for config CDN:** Move widget config to Workers KV edge (global latency improvement)
4. **Horizontal scanner scaling:** Multiple scanner containers sharing the job queue

---

## 15. Troubleshooting

### "Caddy won't start — certificate error"

```bash
# Check Caddy logs
docker compose logs -f caddy

# If origin cert missing:
ls -la /opt/cookiekeeper/ssl/
# If empty, regenerate in Cloudflare dashboard and copy again
```

### "Stripe webhooks not received"

```bash
# Check webhook secret in .env
grep STRIPE_WEBHOOK_SECRET /opt/cookiekeeper/prd/.env

# Verify endpoint in Stripe dashboard
# https://dashboard.stripe.com/webhooks

# Tail API logs
docker compose logs -f api | grep -i stripe
```

### "Database connection refused"

```bash
# Check postgres is running
docker compose ps

# Check credentials in .env match Postgres env vars
grep POSTGRES_PASSWORD docker-compose.yml
grep SPRING_DATASOURCE_PASSWORD .env

# Logs
docker compose logs postgres
```

### "Widget not loading on customer's site"

```bash
# Check widget config endpoint (should be cached by Cloudflare)
curl -I https://cdn.cookiekeeper.eu/cfg/pk_live_123.json
# Should have: Cache-Control: public, max-age=300

# Check API is serving it
curl -H "Host: cdn.dev.cookiekeeper.eu" http://localhost/cfg/pk_live_123.json
```

---

## 16. Support & Documentation

**Support email:** support@cookiekeeper.eu

**Emergency SSH access:** Use `deploy` user (no sudo), escalate to `root` only if necessary

**On-call runbook:** Document in a separate `RUNBOOK.md` file with escalation procedures

---

**Last updated:** 2026-08-18
**Owner:** Yorch
**Status:** Ready for MVP deployment
