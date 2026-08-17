# Dashboard Enrichment Roadmap

Status: planned 2026-08-10, last updated 2026-08-14. Scope: the logged-in customer dashboard
(`dashboard/src/app/[locale]/(app)/**`) and the backend capabilities it needs.

## Progress

| Phase | State |
|---|---|
| Phase 1 — Track 0 (launch blockers) | **Shipped** — every item 0.1–0.7 |
| Phase 2 — Track 1 dashboard home | **Shipped** (see Track 1) |
| Phase 1.5 — ADR-19 widget-config transport (Slices 1 + 2) | **Shipped** |
| Phase 2 — Track 2 settings | **Shipped** — all four surfaces (data, profile, security, notifications) |
| Phase 2 — Track 3 site gaps | **shipped (5 of 5)** — rename, archived view, scan diff, site-cap pre-warning, next-scan date |
| Phase 3 — Track 4, Track 5 | Not started |

**ADR-19 — the widget-config transport — is live.** The widget fetches
`${CDN_BASE}/cfg/{siteKey}.json` and expects a flat config object (`widget/src/config.ts:123-133`); the
backend served only `/api/v1/widget-config/{siteKey}` wrapped in the standard `{ success, data, … }`
envelope. Both halves were built and neither talked to the other. Slice 1 reconciled them:

- **Path** — Caddy's `cdn.` vhost now proxies `/cfg/*` to the API container instead of looking for a file
  on disk (`infra/caddy/Caddyfile`), and `WidgetConfigCdnController` serves `GET /cfg/{siteKey}.json`
  (public, `max-age=300, public`). CORS is owned by Spring (`complyr.cors.paths`), not Caddy — two
  `Access-Control-Allow-Origin` headers on one response is a browser error.
- **Schema** — `WidgetConfigMapper` is the single place the two contracts are reconciled: envelope
  stripped, `bannerVersion`→`version`, category `key`→`id`, text `description`→`message`, `center`
  position folded to `bottom`, and a fourth `buttonText` color derived by WCAG contrast against the
  customer's primary color (no schema or customizer change). Pinned by `WidgetConfigMapperTest`.

With it, **published banner configs are live** — including the per-site `removeBranding` preference from
0.4, which the entitlement layer resolves and the widget honours.

**Slice 2 closed the panel-text gap.** The widget's six panel-only fields used to have no backend source,
so a German visitor got a German banner and an English preferences panel (the widget merges served texts
over its English `DEFAULT_CONFIG`). They now split two ways:

- **Customer-editable** — `preferencesTitle`, `close`, `alwaysActive` and per-category
  `categoryLabels` are stored in `BannerTexts` and edited in the customizer's "Preferences panel"
  section. All four are optional: blank means "use our translation for this language".
- **Server-owned** — `poweredBy` and `opensInNewTab` are injected by `WidgetConfigMapper` from
  `WidgetAttributionTexts` and are deliberately **not** in the stored document. Suppressing the
  attribution is a paid entitlement, so an editable string would be a free way around it.

No migration was needed: the new fields default to blank so existing `config_jsonb` still deserializes,
and `BannerTextDefaults.complete` backfills blanks from the shipped 5-language copy on **every read**
(inside `BannerConfigResponse.from` and `WidgetConfigResponse.from`, so no read path can bypass it).

## Context

Analytics and CSV export — the two things most often assumed missing — **are already shipped**: consent
trend, per-category opt-in, visitor-language split, cookie inventory, range selector, and CSV export for
both the consent log and the analytics trend, all backed by real endpoints under
`/api/v1/sites/{siteId}/analytics` and `/consent-events`.

The real gap is narrower and sharper: **three capabilities we charge for are not implemented**, one of
which is a GDPR storage-limitation exposure on a GDPR product. Everything else is dashboard surface area
that exists on the backend but was never given a UI.

Two decisions taken at planning time:

- **Build** both `removeBranding` (Pro) and `priorityScan` (Business) rather than retract the copy.
- **Enforce** per-plan consent retention properly rather than align copy down or defer.

---

## Track 0 — Compliance and truth-in-advertising (launch blockers) — **shipped**

All seven items landed. The findings below are kept as the record of what was wrong and why each fix
took the shape it did. The one caveat that was carried forward — 0.4's widget-side suppression being
correct but unreachable — is closed: the ADR-19 transport now delivers `removeBranding` to the widget
(see Progress above).

### 0.1 Per-plan consent retention is not enforced — GDPR exposure

`Entitlements.consentRetention` (`billing/Plan.kt:54-55`) is reported to the dashboard but nothing acts
on it. The only deleter is `ConsentEventPartitionReaper`, which is explicitly tenant-blind and drops
whole partitions at a single global `complyr.consent.retentionMonths` (default 36) —
`ConsentEventPartitionReaper.kt:16-32,80`, `ComplyrProperties.kt:184,204-211`.

Consequence: Starter customers are promised 12-month retention and their data is kept for 3 years.
That is an Art. 5(1)(e) storage-limitation problem, not merely a copy bug.

**Approach:** keep the partition reaper as the coarse backstop; add a scheduled per-tenant deletion job
honouring each plan's `consentRetention`. Deletion stays inside the retention job, so the append-only
rule for `consent_events` (no UPDATE/DELETE from application code) is preserved. Amend ADR-16, which
currently records the tenant-blind DROP PARTITION decision.

### 0.2 "Retained up to five years" — no plan ever gets 5 years

`messages/*.json` → `marketing.scan…` (`en.json:145`). Hard overclaim. Copy fix across 5 locales.

### 0.3 "Stored for 3 years" stated unconditionally

`en.json:183` (feature card) and `en.json:279` (FAQ). False for Starter (12 months). Qualify per plan
across 5 locales. Note the dashboard's own `billing.plans` copy is already accurate — fix marketing to
match it, not the reverse.

### 0.4 "Remove Complyr branding" — Pro €19 bullet, zero implementation

`removeBranding` is defined (`Plan.kt:32,86,98`) and returned to the dashboard, but there is no branding
element anywhere in `widget/src/**`. The only "Powered by" in the product is
`dashboard/src/components/policy/hosted-policy.tsx:57`, which is not entitlement-gated.
`EntitlementService.kt:19-20` acknowledges the gap.

**Build:** branding element in the widget (inside the 20KB gate), suppression driven by the entitlement
delivered through `/api/v1/widget-config/{siteKey}`, plus a dashboard toggle. Gate the hosted-policy
"Powered by" on the same entitlement.

### 0.5 "Priority scans" — Business €29 bullet, queue ignores it

`priorityScan = true` for BUSINESS only (`Plan.kt:96`), echoed in `BillingDtos.kt:85`, read by nothing.
The sole "priority" mention in `com/complyr/scan` is an unrelated comment (`ScanWorker.kt:24`).

**Build:** priority column on the scan queue and an `ORDER BY` in the `SKIP LOCKED` claim query.

### 0.6 `/billing` is not auth-gated

`PROTECTED_PREFIXES = ["/dashboard", "/sites"]` (`proxy.ts:17`) omits `/billing`, so it renders
unauthenticated and only fails on the first API call. One-line fix.

### 0.7 Undisclosed limits

- Trial consent-event cap of 1,000 (`ComplyrProperties.kt:596`, applied `PlanResolver.kt:39`) appears
  nowhere in copy or UI.
- Day-15 expiry sets `maxSites = 0` and blocks scans (`Plan.kt:42-52`); copy never says what happens.

Disclose in copy and surface in-app (see Track 5).

---

## Track 1 — Make `/dashboard` a real home — **shipped**

Was a stub: one live Sites card plus two data-less shells, subtitled with a literal placeholder string.

Delivered:

- **Cross-site headline figures** — active sites, consent events (30d), accept-all rate, cookies found
  with the last-scan date. `GET /api/v1/overview` (`OverviewController` → `OverviewService`), four batch
  reads regardless of site count, no N+1. Accept-all rate is `null` — rendered as an em dash — when no
  decisions were recorded, never a 0% that reads as a claim.
- **Action-needed list** — one row per site, severity-ordered server-side: unverified → never scanned →
  policy missing → policy stale → insecure cookies. Order lives *only* in the `OverviewActionKind`
  declaration; the client never re-sorts. An account in good shape gets an explicit all-clear.
- **Trial/plan status strip** — reads the existing `useEntitlement()` query rather than duplicating
  billing state into the overview payload, so `/dashboard` and `/billing` cannot disagree.
- The window is fixed at a trailing 30 days; the adjustable `?range=` stays on per-site analytics, and
  the range still passes through `AnalyticsRangeResolver` so the ADR-16 plan retention floor applies here
  too.

**Not built** (deferred, no consumer yet): the recent-activity feed — scans completed, policy versions
published, banner changes. It needs an event stream we do not currently persist in a queryable shape;
worth revisiting alongside Track 2's notifications, which read the same signals.

---

## Track 2 — Account and settings — **shipped**

The settings **shell** now exists: `(app)/settings/layout.tsx` owns the `<main>` landmark, header and
sub-nav, `/settings` redirects to the first real surface, `/settings` is in the proxy's
`PROTECTED_PREFIXES`, and both the sidebar and `UserNav` link into it. The sub-nav lists only the
surface that exists — the other three drop into it as they are built, no further shell work needed.

- `/settings/data` — **shipped.** **Art. 20 export**: `GET /api/v1/account/export.json`, served
  *outside* the `{ success, data, … }` envelope with a `Content-Disposition` attachment header and
  `Cache-Control: no-store`, downloaded by a plain same-origin `<a download>` so the auth cookies
  attach and nothing about the document lands in client memory. **Art. 17 erasure**:
  `POST /api/v1/account/delete`, password re-authentication (403 `DELETE_CONFIRMATION_FAILED`), Stripe
  cancelled before anything is erased, then one transaction; the UI states plainly what is destroyed
  *and* that consent evidence survives as an anonymized site tombstone until the 3-year partition drop,
  then reports how many sites were deleted vs. kept. Full rationale: **ADR-20**.
- `/settings/profile` — **shipped.** Self-service display name, change password, and change email with
  verify-new-address-first confirmation (ADR-20).
- `/settings/security` — **shipped.** Sign out of all devices.
- `/settings/notifications` — **shipped.** Per-account email notification preferences; the `scanChanges`
  toggle is what Track 3's scan diff gates the scheduled-scan email on.

---

## Track 3 — Close the site-management gaps — **shipped (5 of 5)**

- **Site rename** — **shipped** (`components/sites/rename-site-card.tsx`), consuming the previously
  orphaned `useUpdateSite`.
- **Archived sites view** — **shipped** (`components/sites/site-status-filter.tsx`); the status filter is
  URL state, so an archived set is linkable and archived sites are recoverable again.
- **Scan diff / change detection** — **shipped.** `ScanDiff` + `ScanDiffCalculator` are the single
  definition of "what changed since the previous completed scan" (compared by cookie *name*, since
  `scan_cookies` are replaceable rather than audit rows), shared by the scheduled-scan email gate and the
  dashboard so the two can never drift. Computed on read — no migration, no worker change. The detail view
  does the precise cross-page previous-scan lookup; the history list's "+N new" badge diffs adjacent
  completed scans *within the page* via one batch read, so the oldest scan on a page carries no badge and
  the detail view stays authoritative.
- **Site-cap pre-warning** — **shipped.** `AddSiteDialog` reads `activeSites` vs `limits.maxSites` from
  the entitlement it already fetches (no backend change) and states the cap *before* the domain is typed:
  a last-slot notice at one remaining, and at the cap an explanation plus a link to the plans page. The
  submit stays live on purpose: the count comes from a cache that can lag a change made in another tab,
  and the 403 `SITE_LIMIT_REACHED` guard (taken under an advisory lock) remains the authority. The client
  gate only moves the news forward.
- **Next scheduled scan** — **shipped.** `GET /api/v1/sites/{siteId}/scan-schedule` answers it from
  `ScanScheduleService`, and `RescanCadence.dueAt` is now the single definition of "when is a site due",
  shared with `ScheduledRescanJob.isDue` so the date we promise is the instant the job gates on. The card
  states "paused" rather than a cadence for the two cases the job would skip (archived site, lapsed
  account), and "due in the next nightly window" for a never-scanned or already-overdue site.

---

## Track 4 — Analytics depth

Builds on what already ships.

- **Cross-site analytics** for Pro/Business — multi-site plans have no aggregate view today, which
  weakens the €19/€29 case.
- **Period-over-period deltas** — "+12% acceptance vs prior 30 days".
- **Compliance evidence pack** — one-click bundle of policy version + consent-log extract + scan report.
  The real cash-out of "audit-ready consent logs", and a stronger Business feature than a CSV. Also
  addresses the "CSV export **API**" overclaim (`en.json:267`) by shipping something that exceeds it.
- **Banner performance** (impressions → interaction rate) — *unverified*: not yet confirmed the widget
  emits impression events, only consent events. If it does not, this is a widget change under the
  20KB gate and should be costed accordingly.

---

## Track 5 — Trust and activation polish

- **Onboarding checklist** — add site → scan → customise → embed → verify. The flow exists; nothing
  guides a new user through it. **Shipped (18fdc65)** — first-run getting-started card on the dashboard
  home, backed by real signals from `GET /api/v1/overview` (`OnboardingProgress`), auto-hides once every
  step is done.
- **Empty states** with real next actions. **Shipped (4f217eb).** The one genuine gap was the consent
  log, which showed the same "no events yet" copy whether the log was truly empty or the active *filters*
  matched nothing; it now splits, offering an inline **Clear filters** action in the filtered case. The
  sites list (per-status copy + persistent Add-site dialog) and scan history (Rescan action in the card
  header) already carried real next-actions.
- **Trial status** — days remaining and consent-event usage against the 1,000 cap; graceful expired-state
  UX instead of a silent `maxSites = 0` freeze.
- **Support channel** — **Shipped (mailto).** The FAQ promise ("a real human will reply") is now backed
  by a real contact link in three places: under the FAQ subtitle, in the marketing footer, and in the
  in-app sidebar. All point at `SUPPORT_EMAIL` (`src/lib/site.ts`, env-overridable via
  `NEXT_PUBLIC_SUPPORT_EMAIL`, default `support@complyr.eu`) — one source of truth. A `mailto:` keeps the
  promise honest with no unbuilt form behind it.
  - **Backlog (v1.1):** in-app contact form posting to a backend endpoint (needs an endpoint,
    rate-limit, validation, i18n, and an inbox) — heavier than the MVP warrants; the mailto covers launch.
- **Widget size guard** — **Confirmed wired.** `size-limit` (`dist/v1.js`, `limit: 20 KB`, `gzip: true`)
  runs as a hard non-zero-exit `pnpm size` step in `.github/workflows/ci.yml` and both deploy workflows.
  The "19K" figure was uncompressed; the gate measures *gzipped*, where the widget is ~6.9 KB — ample
  headroom. No code change needed.

---

## Sequencing

| Phase | Contents | Rationale | State |
|---|---|---|---|
| **1** | 0.1 retention, 0.6 auth gate, 0.2/0.3/0.7 copy, 0.4 branding, 0.5 priority scans | Launch blockers: compliance exposure plus features already being charged for | Shipped |
| **1.5** | **ADR-19 widget-config transport** | Without it every published banner config — branding included — is inert | Shipped — Slice 1 (path + schema mapper) and Slice 2 (panel texts) |
| **2** | Track 1 dashboard home, Track 2 settings, Track 3 site gaps | Product stops feeling half-built | Track 1 shipped; Track 2 shipped; Track 3 shipped — Phase 2 complete |
| **3** | Track 4 evidence pack + cross-site analytics, Track 5 onboarding | Justifies the price ladder | Track 4 shipped (Slices A–C); Track 5 onboarding shipped; remaining Track 5 polish open |

## Cross-cutting constraints

Every item inherits the standing rules from `CLAUDE.md`: i18n across all 5 locales (no hardcoded
user-facing strings), `consent_events` stays append-only, EU data residency, no PII in logs, widget
≤20KB gzipped with zero dependencies, Flyway migrations versioned and never edited after apply,
`security-reviewer` mandatory before merge on anything touching auth, billing, or consent ingestion.
