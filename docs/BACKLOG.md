# Complyr Backlog

Single source of truth for what's left. Replaces the old roadmap/status/track checklists
(consolidated 2026-08-18). Architecture and rationale live in [ARCHITECTURE.md](ARCHITECTURE.md);
how to run the stack lives in [RUNNING.md](RUNNING.md).

Ordering rationale: launch-blockers first, then legal/compliance risk (we are a GDPR product —
the bar is "exemplary"), then revenue/UX usefulness, then accepted-risk cleanup.

Status legend: ☐ open · ◐ in progress · ☑ done

---

## Tier 1 — Launch-blocking (before the production `v*` tag)

| # | Item | Why it blocks | Nature |
|---|------|---------------|--------|
| 1 | ☐ **Legal pages: privacy / terms / imprint** | Footer links to `/privacy` `/terms` `/imprint` exist; an EU SaaS cannot launch without them (imprint is mandatory under German TMG). Blocked on entity facts (company name, address, VAT), not engineering. | Needs owner input |
| 2 | ☐ **Install egress firewall on VPS + `verify`** | Code committed (`infra/scripts/egress-firewall.sh` + systemd unit/timer). Fail-closed container egress is a data-exfil control; inert until installed on the box. | Ops (VPS) |
| 3 | ☐ **Run `restore-drill.sh --from-offsite` on VPS** | An untested backup is not a backup. Prove the age-encrypted off-site restore round-trips before relying on it. | Ops (VPS) |

## Tier 2 — Compliance & security hardening (pre-launch or fast-follow)

| # | Item | Why it matters |
|---|------|----------------|
| 4 | ☑ **Escape stored lead email in CSV exports & emails** | CSV sinks already route through shared `CsvCell` (formula-injection + RFC 4180). Residual gap closed: extracted shared `HtmlText` escaper (`8f37fb6`) so email bodies never depend on a composer-private escaper; `ScanEmailComposer` delegates to it. |
| 5 | ☑ **Read-token-in-URL leaks to infra logs** | `Referrer-Policy` set at the Caddy edge; no access logs capture the path. Residual sink closed: `scrubSentryPii` now drops breadcrumbs (`e299a9a`) so navigation/fetch URLs carrying `/api/v1/public-scan/{token}` + `?email=` never reach Sentry. |
| 6 | ☑ **Focus-ring token contrast (a11y HIGH, WCAG 2.2 SC 2.4.11)** | Repointed `--ring`/`--sidebar-ring` (both themes) at the brand accent. The load-bearing `focus-visible:border-ring` (full opacity) now clears the 3:1 non-text floor: light 3.55–3.71:1, dark 7.1–7.9:1. Was ~2.6:1 neutral gray on white. Focus states now read as intentional brand teal. |
| 7 | ☑ **ADR: `stripe_events` erasure linkage** (ADR-21) | Documented: the webhook inbox has no `user_id` FK by design; erasure reaches unprocessed rows by substring-matching the account's handles against the raw `payload` (`redactPendingStripeEvents`), with redact-on-process + a 30d reaper as backstops. Residual PII-at-rest window for repeatedly-failing events is bounded + accepted. |
| 8 | ☑ **ADR: least-privilege / schema-qualified DDL** for the partition reaper (ADR-22) | Documented: the provisioner + reaper run DDL as the single app role and discover/DROP by unqualified `relname`, accepted for the one-schema MVP; fenced by leader-lock + name-revalidation + single-caller. Cross-referenced from ADR-16. Revisit trigger: a second schema/role/deployment. |
| 9 | ☑ **Auth ADRs** — account-enumeration, JWT revocation, lockout DoS/timing (ADR-23) | Documented as four accepted-for-MVP gaps, each with its compensating control and the reason its real fix is disproportionate. Enumeration + timing to be closed together; JWT revocation revisited on incident/compliance trigger. |

## Tier 3 — Useful features / UX (v1.1)

| # | Item | Usefulness |
|---|------|-----------|
| 10 | ☑ **Entitlement-error conflation fix** | Extracted a shared `useEntitlementGate` helper returning a 4-way status (`pending`/`error`/`locked`/`entitled`), so a *failed* entitlement query is no longer rendered as "not entitled → upgrade to Business". Both CSV-export buttons + the cross-site view route through it; `error` shows a neutral retry (`EntitlementGateError`), distinct from the `locked` upsell. i18n `common.entitlementError` added in all 5 locales; hook + both-button gate tests added (12 new). |
| 11 | ☑ **Expensive-export rate-limit tier** | Added a dedicated `EXPORT` tier to `AuthenticatedRateLimitFilter` (suffix-matched on the decoded path, ordered before the GENERAL fallthrough, mirroring VERIFY). The evidence-pack ZIP + CSV export shared the generous GENERAL tier (300/min) despite the pack streaming the full 30-day consent log + all policy languages + latest scan per request; both now share a tight `authExportPerMinute` bucket (default 5/min, VERIFY parity). Security + kotlin reviews (opus): no CRITICAL/HIGH; MEDIUM (10→5) + LOWs applied. |
| 12 | ☑ **In-app contact form** | Replaced the authed sidebar `mailto:` with a real posting form. New `POST /api/v1/support/contact` (JWT-only, per-user throttled via a new tight `CONTACT` tier at 5/min) emails our support inbox with the account's own address as **Reply-To** — the customer address is resolved from the token, never the body, and never lands in `To`; the Subject header is a fixed string and free-text `subject`/`message` are `HtmlText`-escaped into the body (newline→`<br>` *after* escaping). `@NotBlank`/`@Size` caps (150/5000) re-validated server-side; delivery failure surfaces as 503 `CONTACT_DELIVERY_FAILED` (not best-effort). `EmailSender.send()` gained an optional trailing `replyTo` — every existing caller + the Brevo transactional payload stay byte-identical (`@JsonInclude(NON_NULL)`). Dashboard `ContactDialog` (RHF+zod, new `Textarea`/`FormTextarea`) mirrors `AddSiteDialog`; i18n `support.contact.*` in all 5 locales. Four opus reviews (security/kotlin/react/typescript): no CRITICAL/HIGH/MEDIUM surviving — TS MEDIUM (typed `errorCode`) + LOWs applied; added `SupportContactApiIntegrationTest` (401 / validation-boundary / Reply-To delivery) closing the kotlin LOW. The public marketing-site `mailto:` links stay as-is. |
| 13 | ☑ **Analytics depth (Track 4 Slice D) — banner-impression analytics** | Gave the opt-in charts a denominator: count how often the banner is shown, so `interactionRate = decisions / impressions`. Full vertical — widget `sendImpression(siteKey)` fires once per page-load (module sentinel) via a bare keepalive `fetch` to public `POST /api/v1/impression` (no PII, never blocks render); backend `banner_impressions (site_id, day, count)` counter (PK `(site_id, day)`, FK `ON DELETE CASCADE`, V26) — a **disposable aggregate, not audit evidence**, so UPSERTed + DELETE-pruned freely (exempt from the `consent_events` append-only rule); UTC-day-bucketed UPSERT after siteKey→ACTIVE validation; `impressions`+`interactionRate` on `ConsentAnalytics`, `impressions` on `PeriodSummary` (single shared assembler definition); `BannerImpressionReaper` prunes past retention (batched one-tx-per-batch, advisory-lock leader guard, per-run cap); `Tier.IMPRESSION` rate limit (120/min/IP). **Free per-site** (rides inside `ConsentAnalytics`, ungated) + surfaces in the already-Pro+-gated cross-site rollup — no new entitlement flag. Dashboard interaction-rate tile + impression volume with period-over-period deltas. Five opus reviews (security/react/typescript/kotlin/database): 0 CRITICAL/0 HIGH. Fixes applied: **latent reaper bug** `LocalDate.minus(Duration)` throws at runtime → `minusDays(toDays())` + new `BannerImpressionReaperTest` (drain/lock-contended/batch-cap/cutoff); impression denominator now **half-open `[from, to)`** (`day < to`) matching the consent numerator's `created_at < to` + repository boundary test; retention default **90 → 210 days** (prior-comparison window reaches 2× the widest preset, so 90 silently dropped delta baselines); docstring + `interactionRate` DTO doc hardening (raw ratio, UI recomputes to percent — closes 100× trap). |

## Tier 4 — Accepted risk / optional

| # | Item | Note |
|---|------|------|
| 14 | ☐ **Authed throttle on policy generate** | Byte-identical debounce shipped; a vary-a-byte abuse path remains, explicitly accepted. Optional. |
| 15 | ☐ **Shorter-plan over-retention** | Tenant-blind 3yr `DROP PARTITION` can let shorter-plan data outlive its window physically (read-layer floor hides it). Accepted per ADR-16. |
