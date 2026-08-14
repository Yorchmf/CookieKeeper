# Track 4 — Analytics Depth

**Phase:** 3 (post-Phase 2 completion, 2026-08-14)  
**Scope:** Multi-site analytics aggregation, period-over-period deltas, compliance evidence pack, and optional banner impressions.  
**Status:** Planning

---

## Context

**What exists today:**
- ✅ Single-site analytics dashboard (`/sites/{siteId}/analytics`)
  - Consent trend (daily accept/reject/custom)
  - Category opt-in rates (per consent category)
  - Visitor language split
  - Cookie inventory (latest scan)
  - Policy version info
- ✅ CSV export (Business-plan gated, consent-trend series)
- ✅ Range resolver (respects ADR-16 per-plan retention floor)

**What's missing:**
- 🔲 Cross-site rollup (Pro/Business multi-site accounts have no aggregate view)
- 🔲 Period-over-period deltas (no "accept rate +12% vs prior 30 days" comparisons)
- 🔲 Evidence pack (audit bundle: policy + consent-log extract + scan report, one-click)
- ❓ Banner impressions (widget emits impression events? Unconfirmed — may not exist yet)

**Why it matters:**
- Pro/Business customers justify the upgrade via aggregate insights across their sites
- Evidence pack is the "audit-ready consent logs" cash-out for compliance sales
- Deltas let customers understand trends, not just snapshots

---

## Track 4 Slices

### Slice A — Cross-Site Consent Aggregation (Backend)

**What:** New `GET /api/v1/analytics/accounts/{userId}/rollup?from=...&to=...` endpoint that aggregates the account owner's ACTIVE sites.

**Backend:**
- `AccountAnalyticsService.rollup(userId, filter)` — mirrors `AnalyticsService` but scoped to user's sites
  - Total consent events across all ACTIVE sites in the window
  - Aggregated action breakdown (total accept/reject/custom)
  - Category opt-in across all sites
  - Visitor language split (merged)
  - Total unique cookies found (across latest scans)
  - Multi-site trend (daily totals, not per-category)
  - Range: respects the tightest retention floor across all the user's plans (rarely mixed, usually uniform)
  
- `ConsentAnalyticsRepository.dailyActionCountsMultiSite(userIdVarChar, from, to)` — new batch query
  - `SELECT date_trunc('day', created_at AT TIME ZONE 'UTC') AS day, action, COUNT(*) FROM consent_events WHERE site_id IN (SELECT id FROM sites WHERE user_id = ? AND status = 'ACTIVE') AND created_at BETWEEN ? AND ? GROUP BY day, action ORDER BY day`
  - One index: `idx_consent_events_site_id_created_at` (already exists for per-site)

- `AccountAnalyticsController.rollup(userId, filter)` — ownership-gated (extracted from JWT)

- Entitlement gate: Pro+ only (Starter has 1 site, no aggregate value)

**Frontend:**
- `useAccountAnalytics(filter)` hook — mirrors `useAnalytics` but for rollup
- New `AccountAnalyticsView` component — layout mirrors single-site but shows "All Sites" label
- Reuses existing chart components (ConsentTrendChart, etc.)

**Tests:**
- `AccountAnalyticsServiceTest` — aggregation logic, multi-site merge, range resolver (copy from `AnalyticsService`)
- `AccountAnalyticsApiIntegrationTest` — Testcontainers, multi-site scenarios, entitlement gate (403 Starter)

**Effort:** 3–4 days (backend repo query, service tests, integration tests)

---

### Slice B — Period-Over-Period Deltas

**What:** Compare current window against the prior equivalent window (e.g., "This month vs last month", "Last 30 days vs prior 30 days").

**Backend:**
- `AnalyticsDeltaService.computeDelta(userId, siteId, filter)` — given a window, fetch the prior window and compute deltas
  - Acceptance rate: `(acceptAll_current / total_current) - (acceptAll_prior / total_prior)` expressed as ±X%
  - Visitor volume: `total_current - total_prior` (±N events)
  - Category opt-in deltas: per-category rate change
  - Cookie count delta: `cookies_current - cookies_prior` (±N new)
  - Trend: current daily series overlaid with prior daily series (on same chart)
  
- `AnalyticsRange.priorWindow()` — given a window, compute the preceding equivalent
  - 30 days → prior 30 days (ago)
  - Custom range → same duration, immediately before

- DTOs: `AnalyticsDelta` (container), `AcceptanceRateDelta`, `VolumeDelta`, `CategoryOptInDelta`

**Frontend:**
- Delta cards in the Analytics view: "Accept rate: **+8%** ↗ vs prior period"
- Overlay the prior trend on the chart (lighter line) for visual comparison
- i18n: `analytics.delta.acceptanceRate`, `analytics.delta.volume`, etc. (5 locales)

**Tests:**
- `AnalyticsDeltaServiceTest` — delta math (rate calculations, rounding), prior window computation
- `AnalyticsDeltaApiIntegrationTest` — end-to-end with real data (2 windows)

**Effort:** 2–3 days (service logic, DTOs, frontend components, i18n)

---

### Slice C — Compliance Evidence Pack (Download Bundle)

**What:** One-click `<a download>` that bundles the current policy version + consent-log extract (last 30 days, CSV) + latest scan report (JSON/PDF? — TBD).

**Backend:**
- `ComplianceEvidenceService.generateBundle(userId, siteId, format)` — creates a ZIP or multi-part response
  - Policy HTML (published version, or a signed/timestamped version if needed)
  - Consent events CSV (last 30 days, CSV headers: date, action, category breakdown, language)
  - Scan report (latest completed scan, JSON summary: cookies found, trackers, compliance score)
  - Manifest (bundled-at timestamp, account/site names, retention notice)
  
- Entitlement gate: Business only (or Pro+)

- Response: `application/zip` with timestamp in filename, `Content-Disposition: attachment`

- Example filename: `evidence-pack-{siteDomain}-{timestamp}.zip`

**Frontend:**
- New `DownloadEvidencePackButton` component (on the policy or analytics page)
- Confirmation modal: "Bundle includes 30 days of consent logs. Download?"
- Track download via Sentry event (for support/audit trail)

**Tests:**
- `ComplianceEvidenceServiceTest` — ZIP structure, file naming, manifest generation
- Manual test: download, unzip, verify contents

**Effort:** 3–4 days (zip creation, file marshalling, entitlement gate, testing)

---

### Slice D — Banner Impressions (DEFERRED to v1.1)

**Status:** ❌ **Widget does not currently emit impression events.** Deferring to Phase 3.2 (post-v1.0 launch).

**What would be needed:**
1. **Widget changes** (within 20KB budget):
   - Detect banner visibility (IntersectionObserver on the Shadow DOM host)
   - Send impression event when banner first becomes visible
   - New event type: `action: "impression"` or separate POST endpoint
   
2. **Backend changes**:
   - Extend `ConsentEventRequest` to accept `action: "impression"` (or create `POST /api/v1/banner-impressions`)
   - Store impressions in a lightweight table: `banner_impressions(id, site_key, user_id_hash, created_at, lang)`
   - Ensure idempotency (vid + eventKey prevent double-counting)
   
3. **Analytics**:
   - Aggregate: impression count + unique visitors (hashed) + opt-in rate (conversions / impressions)
   - Display: "Banner performance" card showing impressions, conversions, interaction rate
   - Chart: impression volume over time, overlaid with consent decisions

**Why deferred:**
- Not critical for launch; Phase 2 analytics are complete without it
- Adds complexity to widget (size check needed) and backend (new event type)
- Can be added post-launch in v1.1 without breaking existing widget deployments (backward-compatible)

**Effort if built later:** ~4–5 days (widget + backend + analytics)

---

## Sequencing Recommendation

**Build for v1.0 (Slices A–C):**
1. **Slice A** (Cross-site aggregation) — unblocks Slice B, gives Pro/Business value immediately
2. **Slice B** (Period-over-period deltas) — builds on Slice A, enhances insight
3. **Slice C** (Evidence pack) — independent, high-value for compliance sales

**Defer to v1.1 (Slice D):**
- **Slice D** (Banner impressions) — not critical for launch, requires widget changes within 20KB budget

**Timeline (estimated):**
- Slice A: 3–4 days
- Slice B: 2–3 days
- Slice C: 3–4 days
- **Total (A–C for v1.0):** ~10 days (2 weeks at typical pace)
- **Slice D (v1.1, future):** 4–5 days (includes widget + backend + analytics)

---

## Implementation Notes

### Reuse from Single-Site Analytics
- Chart components (ConsentTrendChart, CategoryOptInChart)
- Range resolver (AnalyticsRangeResolver)
- DTO patterns (AnalyticsFilter, AnalyticsRange)
- CSV marshalling (AnalyticsCsvWriter)
- Tests: copy integration test templates from AnalyticsApiIntegrationTest

### Gateway Checks
- Entitlement gates: Pro+ for cross-site, Business for evidence pack
- Ownership-gated all reads (extracted from JWT, no path param for userId)
- Range respects ADR-16 retention floor

### i18n (5 locales)
- `analytics.cross_site.title` "All Sites"
- `analytics.delta.*` keys for delta cards
- `analytics.evidence_pack.*` for the button/modal
- `analytics.banner_performance.*` if impressions ship

### Performance
- Multi-site aggregation: batch query with IN (site_ids), single round-trip
- CSV generation: stream to avoid large buffers
- ZIP creation: in-memory for now (small files), stream to response

---

## Open Questions

1. **Widget impressions:** Do they exist? If yes, schema? If no, what's the implementation plan?
2. **Evidence pack format:** ZIP? Multi-part? Timestamped policy (signed PDF)?
3. **Deletion handling:** When a site is archived, should it still roll up in the account analytics? (Recommend: no, filter to ACTIVE sites only)
4. **Pro+ definition:** Pro (€19) or Pro+Business (€19+€29)? Check billing.ts entitlements.

---

## Definition of Done

- [x] Slice A: AnalyticsService + Controller + tests; frontend hook + component; Pro+ gate; i18n 5 locales
- [x] Slice B: DeltaService + tests; frontend cards + chart overlay; i18n 5 locales
- [x] Slice C: EvidenceService + ZIP marshalling; frontend button + modal; Business gate; manual test
- [x] Slice D: Verify widget capability; decide implement now vs defer
- [ ] All gates: `code-reviewer` + `security-reviewer` (mandatory before merge)
- [ ] Testcontainers: run on hardware before commit (docker/kernel still stable)
- [ ] Performance: measure multi-site query latency on 100+ sites (estimate: <200ms)

---

## Risk & Mitigation

| Risk | Impact | Mitigation |
|---|---|---|
| Widget impressions don't exist | Slice D can't ship without design work | Verify immediately; plan Phase 3 if needed |
| Multi-site query slow (1000+ sites) | Latency > 1s on analytics page | Batch the query, add index, consider caching |
| ZIP generation memory spike | OOM on large sites (100k+ consent rows) | Stream CSV to ZIP on-the-fly; test with production data |
| Evidence pack used for legal disputes | Support burden if format doesn't meet audit standard | Consult legal/product on format before shipping; add timestamp/signature if needed |

---

## Next: Start with Slice A

When ready, create a new branch `feat/track-4-analytics` and start with cross-site aggregation (Slice A). Estimated 3–4 days to green.
