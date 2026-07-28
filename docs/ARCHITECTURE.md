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
cookie_overrides id, site_id, cookie_name, category                     -- customer categorizations
policies         id, site_id, version, language, html, published_at
consent_events   id, site_id, visitor_id (widget UUID), action, categories_jsonb,
                 banner_version, policy_version, lang, ip_hash, ua_trimmed, created_at
                 -- APPEND-ONLY. Partition by month when volume demands (§11).
jobs             id, type, payload_jsonb, status, attempts, locked_until, created_at
```

- `consent_events` is the audit product: no raw IP (SHA-256 with a rotating daily salt — enough for uniqueness/abuse analysis, not re-identifiable), no raw UA, retention default 3 years (configurable, deleted only by the scheduled retention job).
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
- Stripe webhooks: signature verification, idempotent event handling, raw event log table.
- Scanner SSRF protections (§4.4); scanner container has no exposed ports.
- Headers on dashboard + hosted policy pages: CSP (nonce-based), HSTS, X-Content-Type-Options, Referrer-Policy.
- Postgres not exposed publicly (compose-internal network only); VPS firewall allows 22/80/443 only, SSH by key.
- Backups: Hetzner VPS snapshots (daily) + `pg_dump` cron per env shipped to Hetzner Object Storage (encrypted, 30-day rotation). **Restore is tested as part of the launch checklist.**
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
2. **Consent write volume:** partition `consent_events` by month; batch inserts already in place via queue.
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

## 13. MVP Build Order (6 weeks)

1. **W1:** Repo scaffolding, compose stacks, CI pipeline green end-to-end (hello-world images deploying to dev), Caddy + domains + Cloudflare.
2. **W2:** Auth + user/site management (backend + dashboard shell), Flyway baseline.
3. **W3:** Widget core (banner render, consent cookie, Consent Mode v2, script blocking, i18n) + config endpoint + consent ingestion.
4. **W4:** Scanner (queue, crawl, classify, signature DB seed) + scan results UI.
5. **W5:** Banner customizer with live preview, policy generator + hosted pages.
6. **W6:** Stripe billing + entitlements, emails (Brevo), consent log export, prd hardening (backups drill, rate limits, security review pass, load smoke test).

Each week ends deployable to dev. Week 6 ends with prd live behind a waitlist/beta flag.
