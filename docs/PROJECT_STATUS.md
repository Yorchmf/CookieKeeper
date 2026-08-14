# Complyr — Project Status & Roadmap

**Last updated:** 2026-08-14 (commit `92d4712`)  
**Status:** Phase 2 complete. Phase 3 and production deployment pending.

---

## 📊 Phase Progress

| Phase | Contents | Status | Commits |
|---|---|---|---|
| **1** | Track 0: launch blockers (retention, auth gate, branding, priority scans, copy fixes) | ✅ Shipped | `50d90cf` + chain |
| **1.5** | ADR-19 widget-config transport (Slices 1 & 2) | ✅ Shipped | `d3388dc`, `cea4f2f` |
| **2a** | Track 1: dashboard home (overview, action list, trial status) | ✅ Shipped | `b92f1cc` |
| **2b** | Track 2: account settings (data export/erasure, profile, security, notifications) | ✅ Shipped | `8570203` + chain |
| **2c** | Track 3: site management (rename, archived view, scan diff, cap warning, next-scan date) | ✅ Shipped | `92d4712` |
| **3** | Track 4: analytics depth + Track 5: trust & activation | 🔲 Not started | — |

---

## 🎯 What's Shipped (Phase 1 + 2)

### Phase 1 — Launch Blockers
All 7 items completed:
- ✅ **0.1** Per-plan consent retention enforced (V21 ADR-16 read-layer floor)
- ✅ **0.2** Copy fix: "up to 5 years" → accurate per-plan
- ✅ **0.3** Copy fix: "stored for 3 years" → qualified per plan
- ✅ **0.4** Remove Complyr branding (Pro feature, entitlement-gated in widget + dashboard)
- ✅ **0.5** Priority scans (Business feature, queue respects `priorityScan` flag)
- ✅ **0.6** `/billing` now auth-gated (proxy.ts)
- ✅ **0.7** Trial limits disclosed (1,000 consent events, day-15 expiry)

### Phase 1.5 — ADR-19 Widget Config Transport
- ✅ **Slice 1** — Config served at `GET /cfg/{siteKey}.json` (public, CDN-cached), schema mapper reconciles envelope + colors + position + panel texts
- ✅ **Slice 2** — Customer-editable preferences-panel copy stored in `BannerTexts`, server-owned attribution injected by mapper

**Impact:** Published banner configs (including branding suppression) now reach live widgets.

### Phase 2 — Customer-Facing Completeness
- ✅ **Track 1 — Dashboard home**
  - Cross-site headline figures (active sites, consent events 30d, accept-all rate, last-scan date)
  - Action-needed list (unverified → never-scanned → policy-missing → policy-stale → insecure, severity-ordered server-side)
  - Trial/plan status strip reading existing `useEntitlement()` query
  
- ✅ **Track 2 — Account settings**
  - `/settings/data` — GDPR Art. 20 export + Art. 17 erasure (ADR-20, password re-auth, Stripe cancellation, one-txn deletion with tombstones)
  - `/settings/profile` — self-service display name, change password, change email (verify-new-first)
  - `/settings/security` — sign out of all devices
  - `/settings/notifications` — per-account email preferences (tied to scheduled-scan email gate)
  
- ✅ **Track 3 — Site management gaps (5 of 5)**
  - Site rename (PATCH `/api/v1/sites/{id}`)
  - Archived sites view (status filter, URL-as-state, restore action)
  - Scan diff / change detection (single definition shared by email gate + dashboard)
  - Site-cap pre-warning (reads `activeSites` vs `limits.maxSites`, shows notice before domain typed)
  - Next-scheduled-scan date (`GET /api/v1/sites/{siteId}/scan-schedule`, `RescanCadence` shared with job)

---

## 🚀 What's Next

### Option A: Complete Phase 3 (Product Richness)
**Track 4 — Analytics depth** (for Pro/Business justification) → **[Detailed plan: TRACK_4_ANALYTICS_DEPTH.md](TRACK_4_ANALYTICS_DEPTH.md)**
- ✅ **Slice A** — Cross-site rollup endpoint + aggregation charts (Pro+ gate) — 3-4 days
- ✅ **Slice B** — Period-over-period deltas ("accept rate +12% vs prior 30 days") — 2-3 days
- ✅ **Slice C** — Compliance evidence pack (one-click ZIP: policy + consent log extract + scan report) — 3-4 days
- ❓ **Slice D** — Banner impressions → interaction rate (pending widget verification) — TBD

**Track 5 — Trust & activation polish**
- Onboarding checklist (add site → scan → customize → embed → verify)
- Empty states with real next actions
- Trial status UX (days remaining, consent-event usage meter, graceful expired-state instead of silent `maxSites = 0`)
- Support channel (FAQ promises "real human will reply"; no contact link exists)
- Widget size verification (`widget/dist/v1.js` is 19K uncompressed against "under 20KB" claim; confirm CI gate is wired)

### Option B: Launch to Production (Unblock Revenue)
**Currently blocking:** No production deployment has been executed; `main` is ready but `prd` is empty.

**Pre-deployment items** from W6d (all code-complete, untested on VPS):
- Encrypted off-site backups + restore drill
- CX22 Tomcat/Hikari tuning + k6 load smoke test
- Egress firewall (ADR-18, Docker → DNS-rebind SSRF defense) + `verify` pass on VPS
- Uptime dead-man's-switch monitor
- Pre-launch security pass ✅ (fix-now batch + per-account lockout applied)

**Deployment steps** (detailed in LAUNCH_CHECKLIST.md, condensed):
1. VPS access + server-setup.sh (Docker, Caddy, firewall, rclone for Hetzner Object Storage)
2. Egress firewall install + `verify` (ADR-18)
3. Secrets: `.env` with Stripe LIVE keys, Brevo EU API key, JWT secrets, backup age key
4. `docker compose pull && up -d` (auto-migrations via Flyway on startup)
5. Restore drill (backup encryption validates on restore)
6. Load smoke test (k6, ~50 RPS peak, rate-limit tiers verify)
7. Health checks (API health endpoint, dashboard redirect, widget config 404 for unknown sitekey)
8. End-to-end: sign-up flow → add site → scan → policy generation → Stripe checkout (test keys first)
9. DNS + TLS (Caddy auto-provisions via Let's Encrypt)
10. Register uptime monitor (BetterStack / UptimeRobot on 3 endpoints)

**Estimated effort:** 1–2 hours hands-on + waiting for migrations/builds.

---

## 📋 Current Build Status

| Component | Status | Latest Check |
|---|---|---|
| Backend | ✅ Build SUCCESSFUL | `./gradlew ktlintCheck detekt test` green (2026-08-14) |
| Dashboard | ✅ Build SUCCESSFUL | `pnpm lint && test && build` green (24 test files, 198 tests, 2026-08-14) |
| Widget | ✅ 6.71 KB gzipped | Under 20 KB budget (2026-08-14) |
| Git state | ✅ Clean | No uncommitted changes on `feat/site-management` (92d4712) |

---

## 🔐 Security & Compliance Status

| Item | Status | Reference |
|---|---|---|
| GDPR Art. 17 (erasure) | ✅ Implemented | ADR-20, `/settings/data`, 13 review findings fixed |
| GDPR Art. 20 (export) | ✅ Implemented | ADR-20, `GET /api/v1/account/export.json` |
| Consent append-only | ✅ Enforced | `consent_events` ON DELETE RESTRICT + app-code gate |
| Per-plan retention | ✅ Enforced | ADR-16 read-layer floor + `ConsentEventPartitionReaper` |
| EU data residency | ✅ Architected | Hetzner Falkenstein (DE), Brevo (EU), backup encryption (age) |
| SSRF defense | ✅ Built | ADR-18 egress firewall (pending VPS install + verify) |
| Consent logs audit | ✅ Immutable | Server-stamped, siteKey-validated, idempotent |
| Pre-launch security review | ✅ Complete | All findings fixed (`17135cd` + preceding commits) |

---

## ⏳ Residuals & Deferred Items

**From security review (ADR-20):**
- Shorter-plan over-retention accepted: `consent_events_default` can outlive 3-year policy (rare edge case)
- Cross-site export fail-open by design (Sec-Fetch-Site header missing → allowed for curl / Next rewrite)
- No user_id linkage for unprocessed `stripe_events` rows (proposal: ADR)

**From feature roadmap:**
- Track 1 deferred: recent-activity feed (needs queryable event stream we don't persist)
- Track 4 deferred: banner impressions → interaction rate (pending verification widget emits impression events)
- Track 5 deferred: rich active-sessions list with device/IP/last-seen (needs V25 + new PII columns + ADR)

**From operations:**
- No backup restore drill executed on VPS (checklist ready, hardware deployment pending)
- No load test run on production VPS (k6 script ready, pending deployment)
- No uptime monitoring configured (tools chosen, pending registration after deployment)

---

## 🎓 How to Use This Document

1. **Check Phase progress:** Top of the file shows what's shipped (✅) and what's not started (🔲)
2. **Plan next work:** Pick Phase 3 (features) OR launch to production (revenue unlock)
3. **Verify claims:** When in doubt, check the code. This document is a summary; `ARCHITECTURE.md` and commits are authoritative.
4. **Find details:** Each section links to commits or other docs (ADRs, LAUNCH_CHECKLIST.md, etc.)

---

## 📚 Supporting Documents

- **ARCHITECTURE.md** — System design, ADRs, constraints, scaling strategy (§1–12)
- **LAUNCH_CHECKLIST.md** — Detailed deployment procedures (VPS setup, Docker, post-launch ops)
- **DASHBOARD_ROADMAP.md** — Feature roadmap with full context for Phases 1–3 (Tracks 0–5)
- **CLAUDE.md** — Hard constraints and team conventions (GDPR, widget ≤20KB, i18n, consent append-only, etc.)

---

## 🤔 Decision Point

**The team should decide one of two paths:**

### Path A: Launch to Production (Recommended for Revenue)
- Code is complete, tested, and security-reviewed
- Deployment checklist is detailed and step-by-step
- Effort: 1–2 hours hands-on + monitoring post-launch
- Unlocks: Real customers, billing, revenue
- Risk: Moderate (full Testcontainers suite never ran on VPS hardware; restore drill untested IRL)

### Path B: Finish Phase 3 Before Launch (Recommended for Product Strength)
- Completes the customer-visible roadmap (analytics depth, trust polish, onboarding)
- Strengthens Pro/Business pricing justification
- Effort: ~3–4 weeks (4–5 tracks, 20–30 slices)
- Risk: Delays launch; market window narrows
- Benefit: Feature-complete at launch, no "coming soon" copy

**Suggested:** Launch now (Path A) + Phase 3 in v1.1 (60 days post-launch). Customer feedback will inform Track 4/5 priorities.
