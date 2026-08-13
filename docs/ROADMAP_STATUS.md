# Complyr — Roadmap, Tasks & Slices Status

Status: snapshot 2026-08-13. Branch `feat/landing-page`. This is a point-in-time status tree —
authoritative sources remain [ARCHITECTURE.md](ARCHITECTURE.md) (ADRs, structural rationale),
[DASHBOARD_ROADMAP.md](DASHBOARD_ROADMAP.md) (dashboard plan) and [LAUNCH_CHECKLIST.md](LAUNCH_CHECKLIST.md).
When this file disagrees with the code, the code wins — verify before re-opening anything.

Legend: ✅ shipped · 🟡 built, uncommitted · 🔲 not started · ⏳ residual/deferred

---

## 1. Security findings — Art. 17/20 slice (`/settings/data`, commit `48be52e` → fixed in `17135cd`)

Security-reviewer produced 13 findings on the GDPR erasure/export slice. Two binding decisions were
made and must not be re-litigated:

- **H2 fix** = tombstone check in the JWT filter (one indexed lookup per authenticated request,
  covering every current and future endpoint) — deliberately *not* a per-call-site `isErased` whitelist
  and *not* accepting the 15-min token TTL.
- **Scope** = fix everything, including LOW.

All 13 closed in `17135cd fix(account): close every security-review finding on the Art. 17/20 slice`.

```
Security findings (13) — ALL FIXED ✅
├── HIGH (2)
│   ├── H1 ✅ export/erasure needed a dedicated rate-limit tier
│   │        └── AuthenticatedRateLimitFilter ACCOUNT tier, 10/min
│   │           (complyr.rate-limit.auth-account-per-minute)
│   └── H2 ✅ access JWT minted before erasure stayed valid for its 15-min TTL
│            └── ErasedAccountFilter — @Order(LOWEST_PRECEDENCE), one
│                existsByIdAndDeletedAtIsNotNull PK check per authenticated
│                request, 401 on a tombstone. Passthrough keyed on "no JWT
│                principal" so unauthenticated traffic never hits the DB and
│                no future path can be forgotten. Rate-limit filter moved to
│                LOWEST_PRECEDENCE - 1 so the lookup is itself throttled.
├── MEDIUM (6)
│   ├── M1 ✅ tombstone email is derivable → login/reset could re-enter the shell
│   │        └── AuthService.findLiveAccountByEmail gates login, forgotPassword,
│   │           resendVerification (+ isErased check in resetPassword)
│   ├── M2 ✅ delete endpoint was an uncounted password oracle for a stolen session
│   │        └── AccountPasswordService.confirm → shares LoginAttemptService
│   │           budget with the login page; locked account refused before bcrypt
│   ├── M3 ✅ Stripe cancel not idempotent → a mid-erasure DB error could strand
│   │        an undeletable account
│   │        └── StripeApiGateway.cancelSubscription retrieves first, skips if
│   │           already canceled/incomplete_expired
│   ├── M4 ✅ site-create could race the erasure and leave a live site on a tombstone
│   │        └── EntitlementService.requireCanAddSite re-reads the user under the
│   │           SAME per-user advisory lock the erasure takes
│   │           (AccountSiteErasureRepository.acquireUserSiteLock, key = msb xor lsb)
│   ├── M5 ✅ unprocessed stripe_events bodies still held customer_email
│   │        └── redactPendingStripeEvents — stamped processed + nulled (not
│   │           deleted, so a re-delivery dedupes); position(:handle IN payload),
│   │           never LIKE
│   └── M6 ✅ export truncation could be silent
│            └── AccountExportService MAX_SITES = 200 + siteCount on the DTO
├── LOW (5)
│   ├── L1 ✅ DeleteAccountRequest unbounded password field
│   │        └── @Size(max = 200) (a bcrypt-input cap, not a password policy)
│   ├── L2 ✅ tombstone password_hash needed to be structurally valid but unmatchable
│   │        └── AccountPasswordService.unmatchableHash — real bcrypt of a
│   │           discarded random UUID
│   ├── L3 ✅ erasure log line must not re-create PII
│   │        └── id-only log ("Erased account {} …")
│   ├── L4 ✅ export endpoint was cross-site requestable
│   │        └── AccountController rejects an explicit Sec-Fetch-Site: cross-site
│   │           (fail-open otherwise — Next-rewrite/Caddy path and curl still work)
│   └── L5 ✅ me() comment implied isErased was the enforcement point
│            └── comment now points at ErasedAccountFilter as belt-and-braces
└── Refactor forced by the fix
    └── ✅ AccountPasswordService extracted — AccountDeletionService had hit
       detekt LongParameterList (threshold 9, flags AT 9). confirm() +
       unmatchableHash() moved out; acquireUserSiteLock moved to
       AccountSiteErasureRepository to drop the SiteRepository dependency.
```

Gates at commit time: ktlintCheck, detekt, all pure-JVM unit tests, dashboard lint / 127 tests / build
green. Testcontainers suite was compile-only at that point (Docker veth/kernel mismatch); see §4.

---

## 2. Build roadmap (W1–W6 + dashboard phases)

```
Complyr MVP
├── W1–W4  ✅ core platform
│   ├── auth (JWT, cmplyr_at + cmplyr_session marker cookie; proxy.ts dual-check)
│   ├── sites CRUD + entitlements
│   ├── scanner worker (Playwright for Java, `scanner` profile)
│   └── consent ingestion (server-stamped, siteKey-validated, idempotent; ADR-13 origin token)
├── W5  ✅ policy generator
│   ├── backend generator ✅ (byte-identical debounce ~1 version/day)
│   ├── policy dashboard ✅
│   └── hosted /p/{publicId} page ✅
├── W5b ✅ banner customizer
├── Anonymous free-scan funnel ✅ (slices A–G, ADR-12)
├── W6  launch hardening
│   ├── W6a billing ✅ (Stripe checkout/webhooks; stripe_events redact-on-process V13; reaper;
│   │        advisory-lock serialize; per-user authed rate-limit)
│   ├── W6d retention & ops
│   │   ├── ConsentEventPartitionReaper + ADR-16 ✅ (tenant-blind 3yr DROP PARTITION)
│   │   ├── encrypted off-site backups + restore drill 🟡 (run --from-offsite on VPS pre-launch)
│   │   ├── CX22 Tomcat/Hikari tuning + k6 load smoke + uptime dead-man's-switch 🟡
│   │   ├── egress firewall ADR-18 🟡 (install on VPS + `verify` pending)
│   │   └── pre-launch security pass ✅ (fix-now batch + per-account lockout M2)
│   └── landing page ✅ (teal brand, light/dark, 5-locale i18n, full SEO)
├── In-app analytics dashboard ✅ (consent/cookies/policy from own DB; recharts 3; BUSINESS-gated CSV)
└── Dashboard enrichment (see docs/DASHBOARD_ROADMAP.md)
    ├── Phase 1 — Track 0 launch blockers ✅ (0.1–0.7)
    ├── Track 1 — dashboard home ✅ (severity-ordered action list)
    ├── ADR-19 widget-config transport
    │   ├── Slice 1 — per-site config at /cfg/{siteKey}.json ✅
    │   └── Slice 2 — customer-editable preferences-panel copy ✅
    ├── Track 2 — settings
    │   ├── /settings/data — Art. 20 export + Art. 17 erasure (ADR-20) ✅
    │   └── profile / security / notifications 🔲
    ├── Track 3 — site gaps 🔲
    └── Phase 3 — Track 4, Track 5 🔲
```

---

## 3. Tasks & slices — ADR-20 `/settings/data` (the current slice, in detail)

```
ADR-20 — GDPR data export & account erasure  ✅ (48be52e) + security batch ✅ (17135cd)
├── Data model / constraints
│   ├── consent_events.site_id is ON DELETE RESTRICT (V3) → a plain user DELETE is
│   │   impossible for any account that ever served a banner
│   └── V22 — users.deleted_at (tombstone marker; isErased = deleted_at != null)
├── Export (Art. 20)  ✅
│   ├── AccountExportService — everything the account owns, MAX_SITES = 200 + siteCount
│   ├── AccountController.export — GET, rejects explicit Sec-Fetch-Site: cross-site
│   └── dashboard /settings/data export UI
├── Erasure (Art. 17)  ✅ — AccountDeletionService
│   ├── 1. re-authenticate (AccountPasswordService.confirm, shared lockout budget)
│   ├── 2. cancel at Stripe (idempotent) — OUTSIDE the tx; failure erases nothing
│   ├── 3. erase in ONE transaction, under the per-user advisory lock:
│   │      jobs → scan_cookies → scans → cookie_overrides → policies →
│   │      policy_settings → banner_configs → auth_tokens → refresh_tokens →
│   │      subscriptions → public_scan_leads → redactPendingStripeEvents →
│   │      delete consent-free sites → tombstone consent-bearing sites →
│   │      tombstone the user row
│   └── two tombstones survive (user row + consent-bearing sites) purely to keep the
│       append-only audit evidence referentially valid until ADR-16 ages it out
├── Enforcement (H2)  ✅
│   ├── ErasedAccountFilter — the single tombstone gate for all authed endpoints
│   └── AuthService.findLiveAccountByEmail — unauth surfaces (login/reset/resend)
└── Residuals ⏳ (tracked in ARCHITECTURE.md ADR-20)
    ├── shorter-plan over-retention accepted (consent_events_default can outlive 3y)
    ├── cross-site export is fail-open by design (Sec-Fetch-Site absent → allowed)
    └── no user_id erasure linkage for a stripe_events row that never processes → ADR
```

---

## 4. Test verification

- Docker networking was blocked by a kernel/module mismatch (running 7.1.6, modules for 7.1.7);
  every Testcontainers class (~220 tests across 36 `@SpringBootTest` classes) was compile-verified only.
- After reboot the running kernel is **7.1.7** and `docker run` succeeds. The full backend suite
  now runs green: `./gradlew ktlintCheck detekt test` — **BUILD SUCCESSFUL**, including
  `AccountDeletionIntegrationTest` (`leaves a bystander account completely untouched`,
  `redacts only the erased account's unprocessed webhook bodies`).
- The first full run surfaced 5 failures, both clusters **pre-existing/latent** (never executed while
  Docker was broken), *not* regressions from the security commit `17135cd`:
  - **Cluster A (3)** — `AccountDeletionIntegrationTest.seedAccount()` bound a bare `java.time.Instant`
    in a raw `jdbcTemplate.update` (pgjdbc can't infer the SQL type). Fixed: bind `Timestamp.from(now)`.
  - **Cluster B (2)** — `OverviewApiIntegrationTest`'s two multi-site roll-up tests create 2–3 sites via
    the real `POST /api/v1/sites`, but a fresh account is on Trial (1-site cap) → 403 on the 2nd site.
    Fixed: those two tests now grant `Plan.BUSINESS` first (mirrors `BillingApiIntegrationTest`).
