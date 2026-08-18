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
| 4 | ◐ **Escape stored lead email in CSV exports & emails** | CSV formula injection (`=` `+` `-` `@` prefixes) + HTML escaping in outbound mail. Direct injection risk on attacker-controlled input. |
| 5 | ◐ **Read-token-in-URL leaks to infra logs** | Public-scan read token rides in the URL → lands in Caddy/access logs. Add `Referrer-Policy` + Caddy log scrubbing. |
| 6 | ☐ **Focus-ring token contrast (a11y HIGH, WCAG 2.2 SC 2.4.11)** | `--ring` is ~2.3:1, below the 3:1 non-text floor, on every focusable element. Consent products draw regulator + accessibility scrutiny. System-wide token change. |
| 7 | ☐ **ADR: `stripe_events` erasure linkage** | Failing webhook payloads have no `user_id` linkage, so an erasure request can't reach them. Document the decision. |
| 8 | ☐ **ADR: least-privilege / schema-qualified DDL** for the partition reaper | The retention `DROP PARTITION` job runs DDL; document the privilege boundary. |
| 9 | ☐ **Auth ADRs** — account-enumeration, JWT revocation, lockout DoS/timing | Batch of accepted-for-now auth gaps to write down before launch, not silently carry. |

## Tier 3 — Useful features / UX (v1.1)

| # | Item | Usefulness |
|---|------|-----------|
| 10 | ☐ **Entitlement-error conflation fix** | Both analytics upgrade-gate buttons share a helper that conflates "not entitled" with other errors — real UX bug in the paid-upsell path. Cheap fix, good ROI. |
| 11 | ☐ **Expensive-export rate-limit tier** | Evidence-pack ZIP has no dedicated throttle; a Business user could hammer it. Add a heavier tier. |
| 12 | ☐ **In-app contact form** | Replace the `mailto:` with a real posting form (endpoint + rate-limit + validation + i18n + inbox). The `mailto:` is honest in the meantime. |
| 13 | ☐ **Analytics depth (Track 4 Slice D)** | Deferred deeper analytics. Revenue-adjacent for the Business tier. |

## Tier 4 — Accepted risk / optional

| # | Item | Note |
|---|------|------|
| 14 | ☐ **Authed throttle on policy generate** | Byte-identical debounce shipped; a vary-a-byte abuse path remains, explicitly accepted. Optional. |
| 15 | ☐ **Shorter-plan over-retention** | Tenant-blind 3yr `DROP PARTITION` can let shorter-plan data outlive its window physically (read-layer floor hides it). Accepted per ADR-16. |
