# Anonymous Scan Funnel — Design & Slice Plan

**Status:** IMPLEMENTED (through Slice G) — decisions locked §9; landed in `com.complyr.scan`
**Recorded in:** ARCHITECTURE.md §4.4 (SSRF invariant), §5 (`public_scans`/`public_scan_cookies`), §8 (Security Posture), §12 (**ADR-12** recorded)
**Reviewers required before merge:** `security-reviewer` (SSRF/abuse/PII — mandatory), `database-reviewer` (migration), `a11y-architect` (public scan widget)

---

## 1. Why (product rationale)

The core acquisition motion: a visitor enters their domain on the marketing site, gets a free
"are you GDPR-compliant?" verdict (trackers/cookies firing *before* consent), and converts to a paid
signup to fix it. This is the differentiator and the top-of-funnel — self-qualifying, delivers the
aha moment before asking for anything, and SEO-able ("gdpr cookie checker").

**Decision:** build the free scan as a **separate, deliberately-scoped anonymous flow that reuses the
crawler engine** — *not* by removing the ownership guard from the authenticated per-site scanner.

## 2. The invariant this breaks (read this first)

ARCHITECTURE.md §4.4 today: *"scanner only crawls verified customer domains."* That ownership check
(`site.verifiedAt != null`) is one of **two** layers of SSRF defence; the other is
`ScanTargetValidator` (DNS-resolves the host, fails closed unless every address is publicly routable).

The anonymous funnel crawls domains the visitor has **not** verified they own. So we lose the
ownership layer and `ScanTargetValidator` becomes the **sole** SSRF guard. That is acceptable **only
if** we compensate — this whole plan is really "how do we safely give up the ownership layer":

| Lost protection | Compensating control |
|---|---|
| Ownership proves target isn't attacker-chosen | `ScanTargetValidator` public-only resolution is now load-bearing; add off-origin-redirect re-validation on every hop, not just the entry host |
| Only paying customers could burn crawl compute | Per-IP rate limit (new tier) + bot/challenge gate + per-domain result cache + concurrent-public-scan cap |
| Crawl scope bounded by a real owned site | Free scan is **homepage-only, short-timeout** (a "quick" mode), distinct from the paid multi-page crawl |
| No third party abused as crawl target | Same-origin only, no auth/credentials, respect `robots`, single public GET (defensible as "what any browser does"); ToS page |
| No anonymous PII | `ip_hash` (rotating daily salt, same scheme as `consent_events`) for abuse analysis only; never raw IP |

**This table is the crux and needs explicit sign-off** — everything below assumes we accept it.

## 3. Free vs paid boundary (natural upsell line)

- **Free (anonymous):** **homepage-only** single-page load, network + cookie inspection, one verdict
  ("12 trackers, 8 fire before consent → non-compliant"). ~15–30s.
- **Paid (authenticated):** full multi-page same-origin crawl, scan history, scheduled monthly
  re-scans, needs-review categorization, policy generation, script-blocking suggestions.

The free tier is genuinely useful *and* leaves the substantive value behind the login — no artificial
crippling required.

**Reveal model (email gate):** show a **teaser verdict immediately** (headline compliance status +
tracker counts, no login) and gate the **detailed report** (per-cookie breakdown, category detail,
remediation steps) behind an email. Captures every lead at the aha moment without blocking the hook.
The email lands on `public_scans.email` (nullable until provided) and links the lead to a future
signup via `public_token`.

## 4. Data model — CHOSEN: separate `public_scans`, don't overload `scans`

`scans` is FK'd to `sites` (owned, has history, long retention). Anonymous scans have **no owner**,
**short retention (days)**, an `ip_hash`, and an unguessable public read token — a different lifecycle.
Overloading `scans` with a nullable `site_id` maximizes engine reuse but forces the retention job,
the ownership-scoped read API, and every `scans` query to special-case two populations. For a
GDPR-exemplary product I recommend keeping the audit-ish owned-scan table clean.

**Recommended (Option A):** new tables, reuse the *engine* not the *schema*.
```
public_scans        id (UUIDv7), domain, status, public_token (opaque, unguessable),
                    verdict_jsonb (summary counts), email (nullable — captured for full report),
                    ip_hash, created_at, expires_at
public_scan_cookies id, public_scan_id (FK), name, domain, expiry, category, is_known
                    -- same cap slice applies: bounded count + truncated names
```
- Read by `public_token` only (like `policies` public URL), never by owner.
- `email` nullable — set when the visitor requests the detailed report; carried through signup to
  link the lead to the new account.
- Own retention job purges `expires_at < now` — **7-day TTL** (§9).
- Reuses `ScanTargetValidator`, `CookieClassifier`, `ScanCookieMapper` (incl. the Slice 6 caps).

Rejected — Option B (nullable `site_id` on `scans`, `trigger = PUBLIC`): less code now but forces
every `scans` query/retention/read path to special-case two populations. Not chosen (§9).

## 5. Endpoints (both `permitAll`, both rate-limited on a new tier)

```
POST /api/v1/public-scan        { domain }  → { token, status }   (bot-gated + rate-limited)
GET  /api/v1/public-scan/{token}            → { status, verdict, cookiesByCategory }
```
- Mirrors the existing public-endpoint wiring: `SecurityConfig.permitAll`, `RateLimitFilter` new
  `PUBLIC_SCAN` tier (Bucket4j, per-`remoteAddr`, IP never logged/persisted).
- `POST` carries a **honeypot** field + is rate-limited; no third-party CAPTCHA (§9). A separate
  `POST /api/v1/public-scan/{token}/report { email }` captures the email to unlock the full report.
- Async by nature (Chromium takes seconds) → poll, same status-gated polling the dashboard scan UI
  already uses. "Scanning…" progress is good conversion theater.
- **Cache/dedupe:** if a `public_scans` row for `domain` is younger than the **24h cache window**
  (§9), return it instead of enqueuing — caps abuse and cost.
- **Paid-first priority:** the public enqueue writes a normal scan job, but `claimNextId` orders
  authenticated scans ahead of public ones so a free-scan flood can never starve a paying customer
  (single pool, one `ORDER BY` change — §9).

## 6. Conversion bridge

Result page CTA → signup → adopt this domain as the user's first `site` (pre-filled) → verify →
trigger the full authenticated scan. Optionally carry the `public_token` through signup to link the
lead to the account.

## 7. Slice plan (incremental, TDD, each independently shippable + reviewer-gated)

| # | Slice | Notes |
|---|---|---|
| A | Data model + Flyway migration (`public_scans` w/ `email` + `expires_at`, `public_scan_cookies`) + repositories | `database-reviewer`; 7-day TTL column from day one |
| B | Engine extraction: crawl+classify callable for a bare domain in "quick" (homepage-only) mode | preserve unit-testability (crawler stays `@Profile("scanner")`; tests seed via repos, no browser) |
| C | Public enqueue + worker path + `ScanTargetValidator` as sole guard + off-origin redirect re-check + 24h per-domain cache/dedupe + **paid-first `claimNextId` priority** | `security-reviewer` SSRF focus |
| D | Abuse controls: new `PUBLIC_SCAN` rate-limit tier + **honeypot** + `ip_hash` (rotating salt) + concurrent-scan cap | `security-reviewer` |
| E | Public read API: teaser verdict (no gate) + `POST .../report {email}` to unlock detailed DTO | ownership-free, token-scoped |
| F | Marketing landing scan widget: domain input → poll → teaser → email-gate → full report + signup CTA | `a11y-architect`; anti-template design rules |
| G | ✅ Retention job (`PublicScanReaper`, purge `public_scans` past `expires_at`, 7-day) + ADR-12 & ARCHITECTURE.md §4.4/§5/§8/§12 update | mirrors consent reaper; **shipped** |

Cross-cutting: update ARCHITECTURE.md §4.4/§8/§12 (ADR-12) via `doc-updater` once the invariant
change is ratified.

## 8. Reused building blocks (no reinvention)

- `ScanTargetValidator` — SSRF fail-closed public-only resolution (already exists).
- `RateLimitFilter` — add a tier; per-IP Bucket4j, IP-as-key-only (already the pattern).
- `ScanCookieMapper` incl. Slice 6 count/name caps — same attacker-input hardening applies.
- `CookieClassifier` + signature DB — same classification.
- Status-gated polling + scan result UI patterns from the dashboard (Slice 5).
- `ip_hash` rotating-salt scheme from `consent_events`.

## 9. Ratified decisions

| # | Decision | Chosen | Rejected |
|---|---|---|---|
| 1 | SSRF invariant tradeoff | **Accept as designed** — `ScanTargetValidator` is the sole app-layer guard + §2 compensating controls | Require container egress firewall as blocking; no live anonymous crawl |
| 2 | Data model | **Separate `public_scans` / `public_scan_cookies`** (own retention, `ip_hash`, `email`, token) | Nullable `site_id` on `scans` |
| 3 | Reveal model | **Teaser verdict free, detailed report email-gated** | Show everything free; email-gate the whole verdict |
| 4 | Bot protection | **Honeypot + per-IP rate-limit tier** (EU-clean, no processor); escalate to a challenge only if abused | Cloudflare Turnstile (US processor, needs ADR); hCaptcha |
| 5 | Free scan depth | **Homepage only** (~15–30s) | Homepage + N pages |
| 6 | Worker capacity | **Single pool, paid-scan priority** on `claimNextId` | Separate public worker pool; shared FIFO no priority |
| 7 | Cache / retention | **24h per-domain cache window, 7-day `public_scans` TTL** | 1h/24h; 7d/30d |

Because #4 chose no third-party challenge, **no ADR-13 is needed** and no new US data flow is
introduced. #1 changes the documented SSRF invariant, so **ADR-12 + ARCHITECTURE.md §4.4/§8 updates
are still required** (Slice G / doc pass).
```
