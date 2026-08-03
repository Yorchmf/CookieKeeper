# Complyr — Architecture

**Status:** Accepted (v1, 2026-07-28) · **Owner:** Yorch · **Scope:** MVP v1.0 + upgrade paths

GDPR/cookie consent management for small EU businesses. One repo, one primary provider, ~€5/month infrastructure, deployable to dev and prd from day one.

---

## 1. Goals & Constraints

| Constraint | Consequence |
|------------|-------------|
| Solo founder, minimal budget | Free tiers + one cheap VPS; no managed services that cost >€5/mo at zero customers |
| Real product, monetized | Proper environments, CI/CD, backups, monitoring — no "works on my machine" |
| Single repository, easy deploy | Monorepo + Docker Compose; one `git push` deploys |
| Minimal provider sprawl | Everything that can run on the VPS, runs on the VPS |
| EU regulations are the product | **EU data residency is a marketing feature** — Hetzner (Germany) is a deliberate choice, not just a cheap one |
| Widget on customer sites | ≤20KB gzipped, zero deps, async, can never break or slow a customer's page |

## 2. Provider Strategy

Five providers total, each free or near-free, each with a distinct non-overlapping job:

| Provider | Role | Cost |
|----------|------|------|
| **Hetzner Cloud** (Falkenstein, DE) | All compute + data: API, dashboard, scanner, Postgres. One CX22 VPS (2 vCPU / 4GB) | €3.79/mo + €0.76 backups |
| **Cloudflare** (free) | DNS, CDN for the widget (free egress), TLS at edge, basic WAF/rate limiting, caching of public policy pages | €0 |
| **GitHub** (free) | Repo, Actions CI/CD, GHCR container registry | €0 |
| **Stripe** | Billing (monthly + annual), EU VAT via Stripe Tax | pay-per-transaction |
| **Brevo** (free tier, EU-based) | Transactional email (verification, receipts, scan reports) — 300/day free | €0 |

Rejected alternatives and why — see ADRs (§12). Notably: Fly.io removed its free tier for new orgs; Railway's $5 credit is a trial; Vercel/Supabase/Neon each add a provider and weaken the "everything EU, one place" story.

**Total fixed cost: ~€4.55/mo + domain (~€10/yr).** First paying customer covers infrastructure 2×.

## 3. System Overview

```
                        ┌──────────────────────── Cloudflare (free) ────────────────────────┐
                        │   DNS · CDN cache · TLS · rate limiting                           │
                        └──────┬──────────────┬──────────────┬──────────────────────────────┘
                               │              │              │
   Customer's website          │ app.complyr… │ api.complyr… │ cdn.complyr…  (cached hard)
   ┌─────────────────┐         ▼              ▼              ▼
   │ <script async   │   ┌─────────────────────────────────────────────────────────────┐
   │  src=cdn…/v1.js>│   │              Hetzner CX22 · Docker Compose                  │
   │        │        │   │  ┌────────┐  ┌───────────┐  ┌─────────────┐  ┌───────────┐ │
   │   1. GET config ├──►│  │ Caddy  │─►│ dashboard │  │ backend api │  │ scanner   │ │
   │   2. POST consent│  │  │ (TLS,  │  │ Next.js   │  │ Spring Boot │  │ worker    │ │
   └─────────────────┘   │  │ proxy) │─►│ container │─►│ Kotlin      │  │ (same jar,│ │
                         │  └────────┘  └───────────┘  └──────┬──────┘  │ profile=  │ │
                         │                                    │         │ scanner + │ │
                         │                              ┌─────▼──────┐  │ Playwright│ │
                         │   × 2 compose projects:      │ PostgreSQL │◄─┤ browsers) │ │
                         │   complyr-dev / complyr-prd  │ 16 + jobs  │  └───────────┘ │
                         │                              │ queue      │                │
                         │                              └────────────┘                │
                         └─────────────────────────────────────────────────────────────┘
                                    │                              │
                              Stripe (webhooks)              Brevo (SMTP/API, EU)
```

Five containers per environment: `caddy` (shared), `dashboard`, `api`, `scanner`, `postgres`. Dev and prd run as separate compose projects on the same VPS with separate databases, separate `.env` files, and separate subdomains — process-level isolation is acceptable at this scale; upgrade path is a second VPS (§11).

## 4. Components

### 4.1 Backend — `backend/` (Spring Boot 4 — latest stable, Kotlin, JDK 21, Gradle)

Single modular monolith, one deployable jar, two runtime roles selected by Spring profile:

- **Profile `api`:** REST API — auth, site management, banner config, consent ingestion, policy generation, Stripe webhooks, admin.
- **Profile `scanner`:** No web server; polls the Postgres job queue and runs cookie scans with **Playwright for Java** (official MS library — keeps the scanner in Kotlin, same codebase, no separate Node service). Container built from `mcr.microsoft.com/playwright/java` base.

Package layout (by feature, not by layer):

```
com.complyr
├── auth/          # registration, login, JWT (access 15min + refresh rotation), email verification
├── site/          # domains, snippet generation, verification
├── scan/          # job queue, Playwright crawler, cookie signature DB, classification
├── banner/        # banner config CRUD, versioning, widget-config public endpoint
├── consent/       # consent event ingestion (public), audit log queries, CSV export, retention job
├── policy/        # cookie policy generation (templates × 5 languages), hosted page rendering
├── billing/       # Stripe checkout, customer portal, webhook handler, plan entitlements
├── notify/        # email via Brevo (verification, receipts, scan-complete)
└── common/        # security config, rate limiting, error envelope, i18n bundles
```

Key choices:
- **Auth: Spring Security + JWT, rolled ourselves** (plays to existing strength, zero external dependency, EU story intact). Refresh tokens stored hashed in DB, rotation on use. Password hashing: bcrypt.
- **Public endpoints** (`GET /api/v1/widget-config/{siteKey}`, `POST /api/v1/consent`) are unauthenticated, CORS-open, aggressively rate-limited (Bucket4j in-process + Cloudflare rules), and cacheable (widget-config: `Cache-Control: public, max-age=300` so Cloudflare absorbs the read load).
- **Job queue: Postgres, not Redis.** `jobs` table claimed with `FOR UPDATE SKIP LOCKED`, visibility timeout, max-attempts + dead-letter status. One less service to run; fine for thousands of scans/day.

### 4.2 Dashboard — `dashboard/` (Next.js App Router — latest stable, TS, Tailwind, shadcn/ui)

Customer-facing app: register/login, sites, scan results, banner customizer (live preview embedding the real widget), consent log browser + CSV export, policy preview, billing (Stripe customer portal). Also serves the public marketing/landing pages.

- Deployed as a **standalone-output Node container on the VPS** (not Vercel) to keep the single-provider story. Vercel remains a documented escape hatch — nothing in the app assumes local co-location.
- Talks only to the backend REST API. Server state: TanStack Query. No client-side state duplication of server data.
- i18n: `next-intl`, EN/DE/FR/ES/IT.

### 4.3 Widget — `widget/` (vanilla TypeScript, Vite → single IIFE file)

The product's heart and its hardest constraint: **≤20KB gzipped, zero dependencies, async**.

Customer embeds:

```html
<script async src="https://cdn.complyr.eu/v1.js" data-complyr="pk_live_…"></script>
```

Runtime flow:
1. **Immediately** set Google Consent Mode v2 defaults to `denied` (`ad_storage`, `analytics_storage`, `ad_user_data`, `ad_personalization`) via `gtag`/`dataLayer` — before config even loads.
2. Fetch per-site config JSON from `cdn.complyr.eu/cfg/{siteKey}.json` (edge-cached 5 min): colors, position, texts per language, categories, script-blocking rules.
3. If no valid consent cookie → render banner (Shadow DOM, so customer CSS can't break it and ours can't leak). Language auto-detected from `navigator.language`, overridable.
4. On choice: write first-party cookie `cmplyr` (consent snapshot + config/policy version, 12-month expiry), update Consent Mode, unblock scripts (`type="text/plain"` + `data-complyr-category` pattern), fire `navigator.sendBeacon` to `POST /api/v1/consent`.
5. Expose tiny API: `window.Complyr.show()` (reopen preferences), `.consent()` (read current state) — needed for the legally required "withdraw consent as easily as given".

CI gate: `size-limit` fails the build over 20KB gz. Accessibility: focus trap, ESC, ARIA — reviewed by a11y-architect (consent banners are the most-litigated UI in the EU).

Versioned as `/v1.js` (immutable behavior contract); breaking changes ship as `/v2.js`.

### 4.4 Scanner — inside `backend/`, profile `scanner`

- **Triggers:** site added, manual re-scan (plan-limited), monthly scheduled re-scan.
- **Crawl:** homepage + up to 10 same-origin pages (MVP), Playwright Chromium, before-consent state. Collects: cookies (name, domain, expiry, httpOnly), localStorage keys, third-party request hosts, known tracker signatures.
- **Classify** against a seeded cookie signature DB (bootstrap from the Open Cookie Database, CC-BY) → necessary / preferences / statistics / marketing. Unknowns land in a "needs review" bucket the customer can categorize in the dashboard (their categorizations enrich our signature DB over time — a data moat).
- **SSRF hardening:** scanner only crawls verified customer domains, resolves DNS and rejects private/link-local ranges, no redirects off-origin, hard 60s/page + 10min/job timeouts. Runs in its own container with no inbound ports.
- **Anonymous public-scan path** (`com.complyr.scan`, funnel §12/ADR-12): crawls domains the visitor has **not** verified ownership of, so the ownership check above is deliberately given up and `ScanTargetValidator` (fail-closed, public-only DNS resolution, off-origin-redirect re-validation on **every** hop) is the **sole** app-layer SSRF guard. Compensated by: homepage-only "quick" crawl, `PUBLIC_SCAN` per-IP rate tier, per-IP concurrent-scan cap, 24h per-domain cache/dedupe, and an **off-box container egress firewall (blocking deploy requirement)**. `claimNextId` orders authenticated scans ahead of public ones so a free-scan flood can't starve paying customers.
- Scan results feed both the **policy generator** and the **banner's script-blocking suggestions**.

### 4.5 Policy Generator — `policy/`

Template-based (not LLM) cookie policy in 5 languages, filled from scan results + customer business details. Output: hosted page `https://app.complyr.eu/p/{publicId}` (server-rendered, cached, no auth) + copyable HTML block. Policies are **versioned**; consent events reference the policy version active at consent time (audit requirement).

## 5. Data Model (core)

```
users            id, email, password_hash, locale, created_at, verified_at
refresh_tokens   id, user_id, token_hash, expires_at, rotated_from
sites            id, user_id, domain, site_key (public), verified_at, plan_limits_snapshot
subscriptions    id, user_id, stripe_customer_id, stripe_sub_id, plan, status, period_end
banner_configs   id, site_id, version, config_jsonb, published_at        -- append new versions
scans            id, site_id, status, started_at, finished_at, pages_crawled, error
scan_cookies     id, scan_id, name, domain, expiry, category, provider, is_known
public_scans        id (uuid), domain, status, public_token (opaque read key), email (nullable),
                    ip_hash, error, created_at, updated_at, expires_at   -- acquisition funnel, NOT owned
public_scan_cookies id, public_scan_id (FK → public_scans, ON DELETE CASCADE), name, domain,
                    expiry, category, is_known
cookie_overrides id, site_id, cookie_name, category                     -- customer categorizations
policies         id, site_id, version, language, html, published_at
consent_events   id (UUIDv7), site_id, visitor_id (widget UUID), action, categories_jsonb,
                 banner_version, policy_version, lang, ip_hash, ua_trimmed, created_at
                 -- APPEND-ONLY (DB-enforced). Monthly RANGE partitions from day one.
jobs             id, type, payload_jsonb, status, attempts, locked_until, created_at
```

kan- `consent_events` is the audit product: no raw IP (SHA-256 with a rotating daily salt — enough for uniqueness/abuse analysis, not re-identifiable), no raw UA, retention default 3 years (`complyr.consent.retention-months`, default 36), deleted only by the scheduled retention job (`ConsentEventPartitionReaper`, ADR-16).
- **Physical shape (V3 migration):** monthly `RANGE (created_at)` partitions with a `DEFAULT` safety-net partition; composite PK `(id, created_at)` (Postgres requires the partition key in every unique constraint); the id is a time-ordered **UUIDv7** (Hibernate `@UuidGenerator` VERSION_7) for sequential B-tree insert locality on the hot write path. `ConsentEventPartitionProvisioner` (a `@Scheduled` + on-startup job) pre-creates the current + `complyr.consent.partition-lookahead-months` (default 3) months so the `DEFAULT` partition stays empty and reclaimable months can be dropped. **This is a hard requirement, not a nicety:** with the V4 append-only trigger in place, any row that lands in `DEFAULT` can be removed only by dropping the whole `DEFAULT` partition (age-blind) — it can neither be aged out by monthly `DROP PARTITION` nor `DELETE`d, so it would breach GDPR storage-limitation (Art. 5(1)(e)). The provisioner therefore also counts `DEFAULT` on every run and logs an error (→ alert) if it is ever non-empty. It leader-guards across replicas via a transaction-scoped advisory lock and creates partitions with transactional DDL.
- **Retention (dropping partitions past the retention window) is `ConsentEventPartitionReaper`** (ADR-16) — the other half of the partition lifecycle. A `@Scheduled` (cron `0 0 4 * * *`, after the three reapers), cron-only (no startup run — destructive DDL must never fire during boot) job that discovers the attached monthly children from the catalog (`pg_inherits`) and drops every partition whose **entire month** is older than `complyr.consent.retention-months` (default 36 = 3 years). The rule is `month < currentMonth − retentionMonths`, so a partition is dropped only once *no* row it could hold is still within retention (over-retains by up to the partition granularity, never under-retains). It **never** touches `consent_events_default` (age-blind — the provisioner's alert owns it) and re-validates each child name against the `consent_events_YYYY_MM` pattern before it reaches `DROP TABLE`. Each drop is its own short transaction under a distinct transaction-scoped advisory lock (leader-guarded across replicas, mirrors the provisioner) with `SET LOCAL lock_timeout`/`statement_timeout` so a `DROP`'s brief `ACCESS EXCLUSIVE` on the parent fails fast rather than head-of-line-blocking the consent-INSERT path. The window is **tenant-blind** (see ADR-16): it is set to the **longest** plan retention (3 yr), so no customer loses evidence they are entitled to; shorter per-plan windows (Starter 12-mo) are a read-layer product limit, not a physical-deletion promise, which is acceptable because the rows carry no re-identifiable PII. `retention-months` is validated `>= 36` (the **longest** plan retention) at startup — a lower value would let a fat-fingered window irreversibly drop evidence a paying customer is still entitled to — and a test guards that floor against drifting below any plan's entitlement. It also logs a WARN if any partition remains past the horizon after a run, so a stuck lock or timeout can't silently stall retention.
- **Append-only is enforced in the database (V4 migration), not just in code:** a `BEFORE UPDATE OR DELETE` row trigger raises on any row mutation. This applies to **every** role including the schema owner the app connects as — chosen over `REVOKE UPDATE, DELETE` precisely because REVOKE is bypassed by the owner and would otherwise force a separate non-owner runtime role + secret. Retention is `DROP PARTITION` (DDL — does not fire row-level DELETE triggers), so time-based deletion still works while individual-row mutation is impossible. Targeted per-row erasure is intentionally unsupported: there is no direct PII (hashed IP, opaque visitor UUID, trimmed UA) and the log is the evidence GDPR Art. 7(1) requires us to be able to produce. The `ConsentEventRepository` also extends the bare Spring Data `Repository` marker (not `JpaRepository`/`CrudRepository`) so no delete/bulk-update method is inherited — the type system reinforces the DB guarantee.
- `public_scans` / `public_scan_cookies` (Flyway V9) are the **anonymous acquisition funnel** — deliberately separate from `scans`: no owner, no history, a **7-day TTL** (`expires_at` set at row creation), an opaque `public_token` as the sole read key, and `ip_hash` (same rotating-daily-salt scheme as `consent_events`, never a raw IP). Unlike `consent_events` this is **replaceable lead data, not audit evidence**, so DELETE is correct: `PublicScanReaper` (§8) purges expired rows and cookies cascade. V10 adds table-level autovacuum tuning to **both** (mirrors V6 for `consent_idempotency`) because the reaper's insert-right/delete-left churn — amplified by per-scan UPDATEs and the up-to-500-row cookie cascade per deleted scan — would otherwise bloat the btrees at Postgres' default 20% dead-tuple threshold.
- Flyway migrations, Testcontainers-backed integration tests, `database-reviewer` on every migration PR.

## 6. Environments & Configuration

| | local | dev | prd |
|---|---|---|---|
| Runs | `infra/compose.local.yml` on dev machine | VPS, compose project `complyr-dev` | VPS, compose project `complyr-prd` |
| Domains | `localhost:*` | `dev.complyr.eu`, `api.dev.…`, `cdn.dev.…` | `app.complyr.eu`, `api.…`, `cdn.…` |
| DB | Postgres container, seed data | own Postgres container + volume | own Postgres container + volume |
| Email | Mailpit container (catches all mail) | Brevo sandbox/test | Brevo live |
| Stripe | test keys + `stripe listen` CLI | test keys | live keys |
| Config | `.env.local` | `/opt/complyr/dev/.env` | `/opt/complyr/prd/.env` |

One Caddy instance on the VPS routes both environments by hostname and handles TLS (Cloudflare in "Full (strict)" mode with origin certs). All config differences are environment variables only — images are identical across dev/prd.

## 7. CI/CD Pipeline (GitHub Actions)

```
PR → main:
  ├─ backend:   ./gradlew ktlintCheck detekt test        (Testcontainers Postgres)
  ├─ dashboard: pnpm lint && pnpm test && pnpm build
  ├─ widget:    pnpm test && pnpm build && pnpm size     ← fails > 20KB gz
  └─ (paths-filtered: only affected modules run)

merge → main:
  ├─ build 3 images (api, dashboard, widget-assets) → push GHCR, tagged sha + latest
  ├─ widget/config assets → served by Caddy, cache-busted by content hash
  ├─ SSH deploy → dev stack: compose pull && up -d
  └─ smoke test: /actuator/health, dashboard 200, widget config fetch

tag v* → prd:
  ├─ same images (promoted by digest — no rebuild)
  ├─ manual approval (GitHub environment protection rule)
  ├─ SSH deploy → prd stack + Flyway migrates on boot
  └─ smoke test + auto-notify on failure
```

Deploy mechanism: `appleboy/ssh-action` with a deploy-only SSH key, `cd /opt/complyr/{env} && docker compose pull && docker compose up -d`. No config drift: the compose files live in the repo and are rsynced as part of deploy.

## 8. Security Posture

- JWT access (15 min) + rotating refresh tokens (hashed at rest); bcrypt passwords; email verification required before adding sites.
- Rate limiting layered: Cloudflare rules (edge) + Bucket4j (app) on auth and public consent endpoints.
- **Consent-origin token** (ADR-13): the public `POST /api/v1/consent` is unauthenticated + CORS-open by necessity, so a captured payload can be replayed with `curl`. An **optional** stateless HMAC token raises that bar: `GET /api/v1/consent-token/{siteKey}` (uncached, `permitAll`, `CONSENT` rate tier) mints `base64url(siteKey\nexpMillis\norigin).base64url(HMAC-SHA256(secret, payload))`; the consent endpoint recomputes the MAC (constant-time), checks expiry (~2 min TTL), `siteKey` == body, and `Origin` == the token's bound origin — **zero DB writes per page load**, fully stateless. The secret is *configured* (not per-process) so a restart/deploy can't invalidate an in-flight token. **Enforcement is evidence-preserving:** absent token → record; present+valid → record; present+invalid/expired/origin-mismatch → `400`. A tokenless event is *never* rejected — an old embedded widget, a privacy browser that strips `Origin`, or a delayed localStorage retry must never lose audit evidence (constraint #3). The widget only attaches a token while comfortably fresh (< 90 s, under the 120 s server TTL) and keeps its retry queue token-free, so a token can never cause a legitimate event to be dropped. *Residual:* replayable within the TTL, and a scripted attacker can still mint-then-forge `Origin` (the mint is unauthenticated) — bounded, not closed, by the per-IP rate tier. It is defence-in-depth, not a cryptographic origin proof.
- Stripe webhooks: signature verification, idempotent event handling, raw event log table.
- Scanner SSRF protections (§4.4); scanner container has no exposed ports.
- **Anonymous free-scan funnel** (ADR-12): abuse control is a **honeypot** field + a `PUBLIC_SCAN` per-IP rate-limit tier + per-IP concurrency cap + 24h per-domain cache — deliberately **no third-party CAPTCHA** (Turnstile/hCaptcha are US processors; EU-residency rules them out). *Residual:* the honeypot is a client-fillable trap, so a bot that leaves it blank isn't stopped by it — it's defense-in-depth with the rate/concurrency caps, not a standalone gate.
- **Public-scan retention:** `PublicScanReaper` — a daily `@Scheduled` job (cron `0 45 3 * * *`, 15 min after the consent reaper) — deletes `public_scans WHERE expires_at < now()` in advisory-lock-guarded batches (`pg_try_advisory_xact_lock`, distinct key from the consent reaper), cookies cascade. This is the mechanism that **enforces GDPR erasure of the lead PII** (domain + email + `ip_hash`) past the 7-day window. Mirrors `ConsentIdempotencyReaper`.
- *Residual PII leak:* the `public_token` travels in the result URL, so it can reach infra access logs / `Referrer` headers. Mitigated by `Referrer-Policy: strict-origin-when-cross-origin` (above) plus not logging full request URLs/query at the Caddy layer. The stored lead `email` must be escaped in any downstream CSV export (formula-injection) / HTML email.
- Headers on dashboard + hosted policy pages: CSP (nonce-based), HSTS, X-Content-Type-Options, Referrer-Policy.
- Postgres not exposed publicly (compose-internal network only); VPS firewall allows 22/80/443 only, SSH by key.
- Backups: Hetzner VPS snapshots (daily) + `pg_dump` cron per env (`infra/scripts/backup.sh`). Dumps carry visitor PII + append-only consent evidence, so they are **encrypted client-side** with `age` (`pg_dump | gzip | age` — plaintext never touches disk) to a **public** key: the VPS can *write* backups but cannot *decrypt* them (write-only model; the private identity stays offline). Shipped to Hetzner Object Storage (**EU region** — same provider, no new processor per constraint #2) via rclone with a SHA-256 sidecar; rotation ~14-day local / 90-day off-site. **Restore is drilled** by `infra/scripts/restore-drill.sh` (decrypt → restore into a throwaway scratch DB → sanity-verify → drop) as a launch-checklist item and quarterly thereafter.
- `security-reviewer` agent is mandatory on: auth, billing, consent ingestion, scanner, and any endpoint changes.

## 9. Observability

- **Uptime:** BetterStack free tier (or UptimeRobot) probing api health + dashboard + widget CDN URL from outside.
- **Errors:** Sentry free tier — backend (Kotlin SDK) + dashboard. The widget gets **no** Sentry (size + GDPR); widget errors fail silent-safe and are sampled via a tiny beacon to our own API.
- **Logs:** JSON to stdout → `docker logs` + Loki later if needed. No PII in logs (enforced convention + review).
- **Product analytics:** deferred to post-MVP; when added, self-hosted Umami in the same compose stack (GDPR-consistent).

## 10. Billing & Plans

Stripe Checkout + Customer Portal (no custom card UI), Stripe Tax for EU VAT (€0.50/tx accepted for MVP).

| Plan | Price | Limits (enforced in `billing/entitlements`) |
|------|-------|--------|
| Starter | €9/mo (€90/yr) | 1 site, monthly rescan, 12-mo consent retention |
| Pro | €19/mo (€190/yr) | 3 sites, weekly rescan + on-demand, 3-yr retention, remove branding |
| Business | €29/mo (€290/yr) | 10 sites, all Pro + priority scan + CSV export API |

14-day trial, no card required (keeps signup friction low; consent ingestion capped during trial). **Watch item:** if EU VAT admin becomes painful, Paddle (merchant of record) is the documented fallback — billing is isolated in `billing/` precisely so this swap stays cheap (ADR-7).

## 11. Scaling Path (documented now, built later)

Ordered by trigger, not by ambition:

1. **>~50 customers / noisy neighbors:** second VPS — prd gets its own box (compose files already split; move = restore backup + DNS).
2. **Consent write volume:** `consent_events` is already monthly-partitioned (§5); scaling here is automating partition roll-forward (pg_partman) and, if needed, moving cold partitions to cheaper storage.
3. **DB risk tolerance drops (real revenue):** move Postgres to a managed EU offering or a dedicated Hetzner box with streaming replica.
4. **Scan queue depth:** scale scanner containers horizontally (SKIP LOCKED queue already supports N workers).
5. **Widget global latency:** config JSON to Cloudflare Workers/KV at the edge (read path already CDN-shaped).
6. Only then: consider extracting the scanner into a separate service. **Nothing in v1 is built as microservices.**

## 12. Architecture Decision Records (summary)

| # | Decision | Why | Rejected |
|---|----------|-----|----------|
| 1 | Modular monolith (Spring Boot, feature packages) | Solo founder; one deployable; module boundaries preserve later extraction | Microservices, separate scanner service |
| 2 | Hetzner VPS + Docker Compose, dev+prd on one box | ~€4.5/mo total; EU residency is a product feature; one provider | Fly.io (free tier removed), Railway (trial credit), mixed Vercel+Supabase+X (provider sprawl, US processors) |
| 3 | Self-hosted Postgres in compose + tested backups | Zero cost, EU, one provider; risk mitigated by snapshots + pg_dump + restore drills | Supabase/Neon free tiers (extra provider, size caps, non-Hetzner) |
| 4 | Postgres as job queue (SKIP LOCKED) | One less service; transactional enqueue with domain writes | Redis, RabbitMQ |
| 5 | Playwright **for Java** inside backend (profile `scanner`) | One codebase/language; official MS library; same testing stack | Separate Node/Playwright worker service |
| 6 | Own auth (Spring Security + JWT) | Existing strength; no vendor coupling; EU story | Supabase Auth, Auth0/Clerk (cost, US, sprawl) |
| 7 | Stripe + Stripe Tax now; Paddle as documented fallback | MVP spec names Stripe; best docs; Tax handles EU VAT | Paddle first (higher fees, less flexible; revisit if VAT admin hurts) |
| 8 | Dashboard as container on VPS, not Vercel | Single provider, no CORS/env split; standalone Next.js output is small | Vercel free tier (kept as escape hatch) |
| 9 | Widget: vanilla TS + Vite IIFE, Shadow DOM, versioned `/v1.js` | 20KB budget kills frameworks; Shadow DOM isolates styles; version = behavior contract | Preact (~4KB but still budget + habit risk), iframe embed (SEO/UX/Consent-Mode friction) |
| 10 | Template-based policy generation (not LLM) | Deterministic, auditable, free, instant, 5 languages via reviewed translations | LLM generation (cost, hallucination risk in a legal document) |
| 11 | Caddy as reverse proxy | Auto-TLS, 10-line config vs nginx boilerplate | nginx, Traefik |
| 12 | Anonymous free-scan funnel: separate `public_scans` table; `ScanTargetValidator` sole SSRF guard for the unowned path + compensating controls; honeypot + rate limit over CAPTCHA; 7-day TTL + `PublicScanReaper` | Top-of-funnel acquisition; keeps owned `scans` clean (own lifecycle/retention/token read path); EU-residency forbids a US challenge processor; reaper enforces PII erasure. *Residual:* honeypot is client-fillable (defense-in-depth, §8); `public_token` in URL can leak to logs/`Referrer` (mitigations §8) | Nullable `site_id` on `scans` (forces every query to special-case two populations); Turnstile/hCaptcha (US processors, own ADR); no live anonymous crawl |
| 13 | Stateless HMAC **consent-origin token** (§8): optional token minted at `GET /api/v1/consent-token/{siteKey}`, verified on the consent POST (signature + short TTL + `siteKey` + `Origin`); no DB writes; secret is configured, not per-process | Raises the replay bar on the necessarily-open public consent path with zero per-write state; *optional + evidence-preserving* so no legitimate/tokenless event is ever dropped (constraint #3); configured secret survives deploys. *Residual:* replayable within TTL, mint-then-forge-`Origin` possible — bounded by the per-IP rate tier | Stateful nonce store (a per-page-load DB write on the hottest public path, and a new eviction job); requiring the token (would drop events from old widgets / privacy browsers / delayed retries); a full CAPTCHA/PoW challenge (US processors, UX + EU-residency cost) |
| 14 | **Brevo (EU region) as the transactional-email processor**, via HTTP API (`POST /v3/smtp/email`, `BrevoEmailSender`, selected by `complyr.mail.provider=brevo` in dev/prd; Mailpit-SMTP locally). Brevo is Sendinblue SAS (French company); the account **must be provisioned in Brevo's EU data region** — this is an account/runbook requirement, since the API base URL alone does not pin residency. `BREVO_API_KEY` via env only; recipient addresses (PII) never logged and stripped from wrapped delivery errors (constraint #4) | Recipient email + delivery metadata are personal data leaving our infra, so under constraint #2 an external mail processor needs an explicit decision; Brevo is EU-domiciled, GDPR-committed (EU DPA + SCC-free intra-EU processing), generous free tier, simple HTTP API (no SMTP infra). Keeps mail on EU infrastructure. *Residual:* EU-region provisioning is enforced operationally (launch checklist), not by code — a US-region account would silently violate residency | Self-hosted SMTP (deliverability/reputation/DKIM ops we can't afford solo); Postmark/SendGrid/Resend/SES (US-domiciled processors → constraint #2); Mailgun EU (viable alternative, revisit if Brevo deliverability disappoints) |
| 15 | **Sentry SaaS (EU region) for error tracking**, opt-in via DSN. Backend: `sentry-logback` appender attached programmatically (`SentryConfig`, `SmartInitializingSingleton`) — captures `ERROR` logs as events; **not** `sentry-spring-boot-4` (Boot 4 needs the OTel Java agent). Dashboard: `@sentry/nextjs` (server+edge+browser init, `withSentryConfig`), source-map upload disabled. **EU residency enforced in code:** a DSN whose parsed **host** isn't under `*.de.sentry.io` fails startup (both backend + dashboard server) — a *host* parse, not a substring, so a marker in the userinfo/path can't bypass it. **No PII (constraint #4):** `send-default-pii=false` everywhere; backend redacts email/token patterns from message + exception values (the real surface for a log-appender integration, since `request`/`user` are never populated) and drops breadcrumbs (no request-scoped hub → they'd bleed across reused Tomcat threads); dashboard scrubs `request`/`user`. A **blank DSN disables it** (local default). DSN is a public ingest key (browser-inlined `NEXT_PUBLIC_SENTRY_DSN`); dashboard browser derives its environment from the hostname so one build image serves all envs | Operational error visibility is a commercial-robustness requirement; Sentry has a first-class EU region + generous free tier, so residency (constraint #2) is satisfiable without a US processor. Log-appender integration keeps the backend agent-free (CX22 weight). *Residual:* redaction is best-effort heuristic, not a guarantee — the primary control remains "never log PII"; EU-region + blank-default are enforced in code, but a live DSN must still be provisioned in Sentry's EU org (runbook). Client DSN is build-time-inlined, so enabling browser capture needs a CI build arg | `sentry-spring-boot-4` (OTel `-javaagent` + `SENTRY_AUTO_INIT=false` — too heavy for MVP); US-default Sentry region (violates constraint #2); self-hosted Sentry (ops + resource cost on a shared CX22); no error tracking (blind to prod failures) |
| 16 | **Tenant-blind time-based consent retention via `DROP PARTITION`** (`ConsentEventPartitionReaper`, §5). A cron-only `@Scheduled` job drops every monthly `consent_events` partition whose entire month is older than `complyr.consent.retention-months` (default **36 = 3 years**, validated `>= 36`). Drops are discovered from the catalog (`pg_inherits`), name-pattern re-validated (month constrained to 01–12 so an odd child can't throw out of the run), one-per-transaction under a distinct advisory lock with bounded `lock_timeout`; `consent_events_default` is never dropped. The window is the **longest** plan retention (Pro/Business 3 yr) because a monthly partition is shared across all tenants and `DROP` is all-or-nothing — dropping at any shorter window would destroy evidence a longer-retention tenant is entitled to | GDPR storage-limitation (Art. 5(1)(e)) **requires** deleting consent evidence once it is no longer needed, and `DROP PARTITION` is the only sanctioned removal (V4 makes rows un-`DELETE`-able; constraint #3). An instant metadata op, never touches live rows, and mirrors the provisioner's leader-guard/lock-timeout discipline so retention DDL can't stall the hot consent-INSERT path. Cron-only (no startup run) keeps irreversible DDL out of the boot path; the `>= 36` floor (the longest plan retention, test-guarded against drift) guards against a misconfig that would irreversibly under-retain paying customers, and a WARN on any partition still past the horizon after a run makes a stuck/failing retention observable rather than silent. *Residual:* **over-retention of shorter-plan data** — a Starter tenant's rows physically live up to the 3-yr window, not their promised 12-mo; accepted because `consent_events` holds no re-identifiable PII (hashed-rotating-salt IP, opaque visitor UUID, trimmed UA) and the per-plan window is enforced at the read/export layer. The job runs DDL as the **schema owner** (single-role datasource, same as the provisioner) — a least-privilege split-role DDL grant remains a tracked follow-up | Per-row / per-tenant `DELETE` erasure (blocked by the V4 append-only trigger; would need a re-architected trigger + a targeted-erasure path we don't need with no direct PII); a **single global 5-yr** window (over-retains everyone by 2 yr for no benefit — 5 yr is only the CLAUDE.md *ceiling*, not a current plan); a **per-plan physical** window (impossible without one partition set per retention tier — heavy, and still tenant-mixed within a tier); `DETACH … CONCURRENTLY` before `DROP` (can't run in a transaction, and the bounded `lock_timeout` already prevents head-of-line blocking) |

## 13. MVP Build Order (6 weeks)

1. **W1:** Repo scaffolding, compose stacks, CI pipeline green end-to-end (hello-world images deploying to dev), Caddy + domains + Cloudflare.
2. **W2:** Auth + user/site management (backend + dashboard shell), Flyway baseline.
3. **W3:** Widget core (banner render, consent cookie, Consent Mode v2, script blocking, i18n) + config endpoint + consent ingestion.
4. **W4:** Scanner (queue, crawl, classify, signature DB seed) + scan results UI.
5. **W5:** Banner customizer with live preview, policy generator + hosted pages.
6. **W6:** Stripe billing + entitlements, emails (Brevo), consent log export, prd hardening (backups drill, rate limits, security review pass, load smoke test).

Each week ends deployable to dev. Week 6 ends with prd live behind a waitlist/beta flag.
