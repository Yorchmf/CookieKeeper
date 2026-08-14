# Track 4 Slice A — Task Breakdown

**Branch:** `feat/track-4-analytics`  
**Estimated:** 3-4 days (TDD approach)  
**Status:** Ready to start

---

## Phase 1: Backend Foundation (1-1.5 days)

### Task 1.1 — Database Query (Backend Unit Tests First)
**File:** `backend/src/test/kotlin/com/complyr/analytics/AccountAnalyticsRepositoryTest.kt`

**Test case:** Multi-site daily action counts
- Given a user with 3 ACTIVE sites over a 30-day window
- Query `dailyActionCountsMultiSite(userId, from, to)`
- Returns aggregated daily rows: `date, action (ACCEPT_ALL/REJECT_ALL/CUSTOM), count`

**Implementation:**
- Extend `ConsentAnalyticsRepository` with new method
- SQL: `SELECT date_trunc('day', created_at AT TIME ZONE 'UTC') AS day, action, COUNT(*) FROM consent_events WHERE site_id IN (SELECT id FROM sites WHERE user_id = ? AND status = 'ACTIVE') AND created_at BETWEEN ? AND ? GROUP BY day, action ORDER BY day`

### Task 1.2 — Service Layer (Unit Tests First)
**File:** `backend/src/test/kotlin/com/complyr/analytics/AccountAnalyticsServiceTest.kt`

**Test cases:**
1. Aggregates consent across 2+ ACTIVE sites
2. Respects range resolver (ADR-16 retention floor)
3. Excludes ARCHIVED sites
4. Returns zero when no sites exist
5. Returns zero when no events in window

**Implementation:**
- New `AccountAnalyticsService(siteRepository, consentAnalyticsRepository, rangeResolver, ...)`
- Method: `summarize(userId: UUID, filter: AnalyticsFilter): AccountAnalyticsResponse`
- Mirrors `AnalyticsService.summarize()` pattern but:
  - Loads all user's ACTIVE sites (not a single site lookup)
  - Aggregates daily counts
  - Computes action breakdown (total acceptAll/rejectAll/custom)
  - Computes category opt-in (aggregated across all sites)
  - Computes visitor language split (merged)

### Task 1.3 — Controller Endpoint (Integration Tests First)
**File:** `backend/src/test/kotlin/com/complyr/analytics/AccountAnalyticsApiIntegrationTest.kt`

**Test cases:**
1. GET `/api/v1/analytics/accounts/rollup` returns 401 when unauthenticated
2. Ownership-gated: User A cannot read User B's rollup
3. Pro+ plan: returns data; Starter plan: 403 ENTITLEMENT_REQUIRED
4. Multi-site aggregation with real Testcontainers data
5. Range filter works (from/to params)

**Implementation:**
- New `AccountAnalyticsController`
- Ownership gate: extract `userId` from JWT principal (no path param)
- Entitlement gate: require Pro+ (`plan != STARTER`)
- Delegate to `AccountAnalyticsService.summarize(userId, filter)`
- Return: `ApiResponse<AccountAnalyticsResponse>`

---

## Phase 2: DTOs & Serialization (0.5 days)

### Task 2.1 — Response DTOs
**File:** `backend/src/main/kotlin/com/complyr/analytics/dto/AccountAnalyticsDtos.kt`

```kotlin
data class AccountAnalyticsResponse(
    val range: AnalyticsRange,
    val consent: AccountConsentAnalytics,
    // Metrics for future slices (B/C):
    val siteCount: Int,  // For delta calculations, evidence pack
)

data class AccountConsentAnalytics(
    val totalEvents: Int,
    val byAction: ActionBreakdown,
    val trend: List<ConsentTrendPoint>,  // Daily totals (no per-category drill)
    val categoryOptIn: List<CategoryOptIn>,  // Aggregated across all sites
    val languageSplit: List<LanguageCount>,  // Merged
)
```

---

## Phase 3: Frontend Foundation (1-1.5 days)

### Task 3.1 — API Client & Hook (Unit Tests First)
**File:** `dashboard/src/lib/api/account-analytics.ts` + `dashboard/src/hooks/use-account-analytics.ts`

**Test cases (RTL/vitest):**
1. `useAccountAnalytics(filter)` fetches and caches
2. Respects stale time from query client (30s)
3. Handles error states
4. Invalidates on related mutations (if any)

**Implementation:**
- Client: `getAccountAnalytics(filter: AnalyticsFilter): Promise<AccountAnalytics>`
- Hook: mirrors `useAnalytics` pattern, same query client config

### Task 3.2 — View Component (RTL Tests First)
**File:** `dashboard/src/components/analytics/account-analytics.tsx` + test

**Test cases:**
1. Renders "All Sites" analytics when data loads
2. Shows loading skeleton while fetching
3. Shows error state if query fails
4. Reuses existing chart components (ConsentTrendChart, etc.)
5. Displays site count + range info

**Implementation:**
- Reuse: `ConsentTrendChart`, `CategoryOptInChart`, `LanguageSplit`, `StatTile`
- New: `AccountAnalyticsView` container component (pairs with existing `AnalyticsView`)
- Layout: match single-site analytics design, just labeled "All Sites"

### Task 3.3 — i18n (5 locales)
**Files:** `dashboard/messages/{en,de,fr,es,it}.json`

**Keys added:**
- `analytics.cross_site.title` "All Sites"
- `analytics.cross_site.subtitle` "Aggregated consent and cookie data across all your active sites"
- `analytics.cross_site.site_count` "Active sites: {count}"

---

## Phase 4: Integration & Polish (0.5-1 days)

### Task 4.1 — Entitlement Gate Test
Verify the 403 gate in `AccountAnalyticsApiIntegrationTest.kt` passes.

### Task 4.2 — Route/Navigation (Optional)
If you want a separate `/analytics` (account-level) page in addition to `/sites/:id/analytics` (single-site):
- New page: `dashboard/src/app/[locale]/(app)/analytics/page.tsx`
- Nav link in settings or dashboard
- Route guards: Pro+ only

(Can defer this to Slice B or later if you prefer a feature flag initially.)

### Task 4.3 — Build & Lint
```bash
cd backend && ./gradlew ktlintCheck detekt test
cd dashboard && pnpm lint && pnpm test && pnpm build
```

---

## Definition of Done

**Backend:**
- ✅ `AccountAnalyticsService` unit tests green (no Testcontainers)
- ✅ `AccountAnalyticsRepository` integration tests (Testcontainers)
- ✅ `AccountAnalyticsController` integration tests (multi-user, entitlement gate)
- ✅ All DTOs in place
- ✅ `ktlintCheck detekt test` pass
- ✅ Security review: ownership + entitlement gates verified

**Frontend:**
- ✅ `useAccountAnalytics` unit tests
- ✅ `AccountAnalytics` component RTL tests
- ✅ i18n strings in 5 locales
- ✅ `pnpm lint && test && build` pass
- ✅ Accessibility: chart labels, ARIA, etc.

**Cross-cutting:**
- ✅ Both gates (code-reviewer + optional security-reviewer on controller)
- ✅ Git commit with message referencing Track 4 Slice A

---

## Sequencing

**Recommended order (TDD-first):**
1. 1.1 Repository test + implementation (green)
2. 1.2 Service tests + implementation (green)
3. 2.1 DTOs
4. 1.3 Controller tests + implementation (green)
5. 3.1 API client + hook tests + implementation
6. 3.2 Component tests + implementation
7. 3.3 i18n
8. 4.1-4.3 Integration, build, gates

**Parallelizable:**
- Tasks 1.1, 1.2 can start independently
- Tasks 3.1, 3.2 can start once backend DTOs are stable (Task 2.1)

---

## Architecture Decisions

1. **Multi-site query scope:** Only ACTIVE sites (archived excluded) — keeps the metric focused on customer's current property portfolio
2. **Trend granularity:** Daily totals (not per-category) — avoids explosion of trend points; category opt-in stays disaggregated for insight
3. **Range validation:** Delegates to existing `AnalyticsRangeResolver` — ensures ADR-16 floor is respected, no special logic needed
4. **Entitlement:** Pro+ (not Starter) — single-site doesn't justify an aggregate view
5. **Ownership gate:** Extracted from JWT principal, not path param — cleaner API surface, future-proof if we add personal vs. team dashboards

---

## Known Risks

| Risk | Mitigation |
|---|---|
| Multi-site query slow (1000+ sites) | Start with index `idx_sites_user_id_status`, measure latency, consider read-only replica if needed |
| Testcontainers flaky on VPS | Already stable on dev machine (kernel 7.1.7); monitor before production deployment |
| I18n key drift | Update all 5 files in one edit, use a script to verify keys match |

---

## Next Steps

1. ✅ Create branch `feat/track-4-analytics` (done)
2. ⏳ Start Task 1.1 (repository test)
3. ⏳ Build in sequence 1.1 → 1.2 → 2.1 → 1.3 → 3.x
4. ⏳ Commit when all 4 phases are green
5. ⏳ Slice B: period-over-period deltas (next feature)
