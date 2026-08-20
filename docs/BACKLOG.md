# CookieKeeper Backlog

Single source of truth for what's left. Replaces the old roadmap/status/track checklists
(consolidated 2026-08-18). Architecture and rationale live in [ARCHITECTURE.md](ARCHITECTURE.md);
how to run the stack lives in [RUNNING.md](RUNNING.md).

Ordering rationale: launch-blockers first, then legal/compliance risk (we are a GDPR product —
the bar is "exemplary"), then revenue/UX usefulness, then accepted-risk cleanup.

Tiers 1–4 are the road to the first production tag. Tiers 5–8 are the "more than an MVP" programme
agreed on 2026-08-20 and are listed **in build order** — within them, item number *is* the sequence.
The ordering principle there is different: the cheapest items whose absence costs the *customer*
money go first, the flagship compliance feature next, then proof and reach, then the index page that
makes all of it findable.

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
| 14 | ☑ **Authed throttle on policy generate** | Closed the accepted vary-a-byte version-spam gap: added a dedicated tight `POLICY` tier (5/min/user, VERIFY/EXPORT/CONTACT parity) to `AuthenticatedRateLimitFilter`. It is the filter's first **method-scoped** tier — `POST /api/v1/sites/{id}/policy` is the heavy write, but the *same* path serves the cheap `GET` current-policy read the dashboard hits on every policy-page view, so the branch gates on POST and the read stays on GENERAL. Sites sub-resource matching extracted into a behavior-preserving `sitesTier` helper (keeps detekt complexity under threshold). Two opus reviews (security + kotlin): no CRITICAL/HIGH/MEDIUM; kotlin LOWs applied (method-scoping regression test + comment nits), security LOWs pre-existing/immaterial (trailing-slash coupling shared with VERIFY/EXPORT; concurrency already bounded by the per-site advisory lock). |
| 15 | ☐ **Shorter-plan over-retention** | Tenant-blind 3yr `DROP PARTITION` can let shorter-plan data outlive its window physically (read-layer floor hides it). Accepted per ADR-16. |

---

# Beyond MVP (Tiers 5–8)

The product is already broad. These are the places where it is *shallow* in exactly the spots a
customer is paying us to be right about. Build strictly in the order below — each tier assumes the
one above it has landed.

## Tier 5 — Consent correctness (build first)

Cheap to build, and the customer pays for our being wrong. Nothing here is visible on a feature
comparison table, which is precisely why competitors at this price point skip it.

| # | Item | Why it matters |
|---|------|----------------|
| 16 | ☑ **Google Consent Mode v2 depth** | **Done.** `widget/src/consent-mode.ts` now derives all **seven** signals from one place (`signalsFor`) and sends every one of them on both the default and the update push — an omitted signal is assumed *granted* by Google, so completeness is the correctness property. `functionality_storage`/`personalization_storage` map to `preferences`, `security_storage` is granted (locked-on `necessary`), and a category the site's banner does not offer stays denied rather than being inferred. `ads_data_redaction` is on from the first paint and lifts only when `marketing` is granted; `url_passthrough` is opt-in per embed via `data-complyr-url-passthrough`, because it rewrites the host page's own links and is the site owner's call. 7.09 kB gz (gate 20 kB), 13 tests. **`region`-scoped defaults were deliberately NOT built** — see #16a. |
| 16a | ☑ **`region`-scoped Consent Mode defaults + banner geo-targeting** (deferred from #16) | **Done.** Built as the coherent feature the deferral asked for, not a flag on the default push: one embed attribute, `data-complyr-regions="gdpr"`, drives **both** halves, so the two can never disagree. *Signals:* `setConsentDefaults` splits into an all-`denied` push scoped to `region: GDPR_REGIONS` (EU 27 + the outermost regions Cloudflare reports separately + EEA + UK/Crown Dependencies/Gibraltar + CH) followed by an all-`granted` global fallback — scoped first, as Google requires, and no `wait_for_update` on the fallback since nothing will ever update it. *Banner:* the existing `GET /api/v1/consent-token/{siteKey}` mint (already on the banner path, `no-store`, never CDN-cached, no DB work) now also returns `region`: `"gdpr"`, `"other"` or `null`, classified by `GdprRegions.classify` from Cloudflare's `CF-IPCountry`. Only the bucket reaches the page — the country code never leaves the server, nothing is persisted, and a spoofed header only ever costs the sender a banner they could have skipped. *Fallback when geolocation is unavailable:* fails open — failure, timeout, `XX`, `T1`, or a malformed header all yield `null` and the banner shows; only a positive `"other"` suppresses one. *How the consent log records "not shown":* it does not, on purpose — no cookie, no consent event, no impression beacon, because nothing was asked and nothing was chosen; recording a choice would be false audit evidence. Blocked `text/plain` tags are released instead, so script-blocking agrees with the signals. `Complyr.show()` bypasses the gate (withdrawal must work everywhere), and an unrecognised attribute value `warn()`s and falls back to showing everyone. Dashboard: two switches on the site detail page rewrite the snippet live (`withEmbedOptions`), which also finally surfaces `data-complyr-url-passthrough` from #16. 7.62 kB gz (gate 20 kB), 104 widget tests, 294 dashboard tests. |
| 17 | ☐ **Configurable consent lifetime** | `COOKIE_MAX_AGE_SECONDS` (`widget/src/constants.ts:109`) is hardcoded to 12 months. CNIL guidance is 6, and a customer's own DPO will eventually ask for it. Becomes a per-site banner-config field defaulting to today's 12 months, so no existing site changes behaviour on deploy. |
| 18 | ☐ **Re-prompt when the site's tracking materially changes** (flagship) | The `version` in the consent cookie (`widget/src/storage.ts:19`) is the *payload schema* version, not the banner/policy version. A site that adds a marketing tracker in March keeps serving consents collected in January against a cookie list that never mentioned it — consent that is not valid for the new purpose. Stamp the banner-config version into the cookie and re-ask when a **material** change lands: a category newly in use, not a colour edit. Reuse `ScanDiffCalculator` — it is already the single definition of "what changed"; do not invent a second one. Caveat to design for and to say in the UI: a re-prompt wave resets the impression/interaction denominators, so analytics will show a step change that is ours, not the customer's. |

## Tier 6 — Proof, reach & polish

Turns "we run a banner for you" into "we can show you it is actually working".

| # | Item | Why it matters |
|---|------|----------------|
| 19 | ☐ **Post-install blocking verification** | `ComplianceAnalyzer` already emits a `pre_consent_tracking` finding and `script-blocking.ts` blocks *tagged* scripts — but nothing tells a customer "Complyr is installed and Google Analytics still fires before consent: you never tagged this script, here is the line to change." That is the most common way an SMB ends up non-compliant *while paying for a consent tool*, and it is worse than having no banner, because the banner is a written claim the site does not honour. Needs per-tracker remediation copy plus a "still unfixed after N days" nudge riding the existing rescan schedule. |
| 20 | ☐ **Embeddable cookie table** | A `<div data-complyr-policy>` that renders the current cookie list on the customer's **own** policy page, not only the hosted `/p/{publicId}`. Their lawyer-approved page then stays in sync with the latest scan by itself. Must hold the widget's hard constraints: size gate, zero dependencies, never blocks host render. |
| 21 | ☐ **Banner accessibility — WCAG 2.2 AA + EAA conformance** | The European Accessibility Act has applied since June 2025 and covers exactly our e-commerce customers. Audit the banner and preferences dialog (keyboard trap, focus order and restore, contrast, reduced motion, dialog semantics), fix, then publish a conformance statement. Route via `a11y-architect` per CLAUDE.md. Compliance asset *and* marketing asset — most competitors' banners fail this. |

## Tier 7 — Make all of it findable

| # | Item | Why it matters |
|---|------|----------------|
| 22 | ☐ **In-app feature index — one page listing everything the product does** | Customers currently discover features by accident. One page listing every capability, what it is for, and a link that goes straight to using it. **Entitlement-aware**: a plan-locked feature appears as locked with its upgrade path rather than being invisible, so the page is the onboarding surface and the upsell surface at once — reuse `useEntitlementGate`'s 4-way status rather than a fresh gate. i18n in all 5 locales. Deliberately last in the programme: it is the index *of* items 16–21, so it is written once they exist and is updated whenever anything above it lands. |

## Tier 8 — Growth epics (not before v1.1 ships)

| # | Item | Why it matters |
|---|------|----------------|
| 23 | ☐ **Multi-user / agency accounts** | One account is one person today. Agencies are the best distribution channel into SMB, so this is the item most likely to change the growth curve — but roles, invites and permissions touch every endpoint and every ownership check (`requireOwnedSite` and its siblings all assume `userId` *is* the owner). A real epic with its own ADR, not a feature. Do not start it before v1.1 ships. |
