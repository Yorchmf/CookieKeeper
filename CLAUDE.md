# Complyr

GDPR/cookie consent management micro-SaaS for small European businesses. Dead-simple, affordable (€9–29/mo), EU-focused (GDPR, ePrivacy, DSA). This is a **commercial product**, not a hobby project — code quality, security, and operational robustness matter.

Full architecture and rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Read it before making structural changes.

## Monorepo Layout

```
complyr/
├── backend/          # Spring Boot 4 (Kotlin, Gradle Kotlin DSL) — REST API, auth, billing,
│                     #   consent ingestion, policy generation, scanner worker (Playwright for Java,
│                     #   activated via Spring profile `scanner`)
├── dashboard/        # Next.js (App Router, latest stable) + TypeScript + Tailwind + shadcn/ui (customer dashboard)
├── widget/           # Embeddable consent banner — vanilla TypeScript, Vite build, ZERO runtime deps
├── infra/            # docker-compose files (local/dev/prd), Caddyfile, deploy scripts, backup scripts
├── .github/workflows/# CI/CD pipelines
└── docs/             # Architecture, ADRs, runbooks
```

## Commands

| Task | Command |
|------|---------|
| Full local stack | `docker compose -f infra/compose.local.yml up` |
| Backend build + test | `cd backend && ./gradlew build` |
| Backend run (local) | `cd backend && ./gradlew bootRun` |
| Backend lint | `cd backend && ./gradlew ktlintCheck detekt` |
| Dashboard dev | `cd dashboard && pnpm dev` |
| Dashboard lint/test/build | `cd dashboard && pnpm lint && pnpm test && pnpm build` |
| Widget dev | `cd widget && pnpm dev` |
| Widget build + size gate | `cd widget && pnpm build && pnpm size` |

Package manager for JS workspaces: **pnpm**. Backend: Gradle wrapper, JDK 21.

## Hard Constraints (non-negotiable)

1. **Widget ≤ 20KB gzipped, zero dependencies, async, never blocks host page render.** CI fails the build if the size gate is exceeded. No framework code in `widget/` — vanilla TS only.
2. **EU data residency.** All customer/visitor data stays on EU infrastructure (Hetzner Falkenstein/Nuremberg). Never introduce a US-hosted data processor without an ADR.
3. **Consent logs are append-only audit evidence.** Never UPDATE or DELETE rows in `consent_events` from application code. Retention/erasure happens only via scheduled retention jobs.
4. **We are a GDPR product — we must be exemplary.** No raw IPs at rest (hash with rotating salt), no PII in application logs, no third-party trackers in the widget or public pages.
5. **Widget must set Google Consent Mode v2 defaults to `denied` before any vendor script runs.**
6. **i18n from day one:** EN, DE, FR, ES, IT. No hardcoded user-facing strings — all UI text through the i18n layer (dashboard: message catalogs; widget: per-site config; backend: message bundles for emails/policies).
7. **Secrets** only via environment variables / `.env` files that are gitignored. `.env.example` documents every variable.

## Environments

| Env | Where | Trigger |
|-----|-------|---------|
| local | `docker compose` on dev machine (includes Postgres + Mailpit) | manual |
| dev | Hetzner VPS, compose project `complyr-dev`, `dev.` subdomains | auto-deploy on merge to `main` |
| prd | Same VPS (v1), compose project `complyr-prd`, production domains | manual approval on git tag `v*` |

Deploys are GitHub Actions → build images → push to GHCR → SSH to VPS → `docker compose pull && up -d`. Never deploy by hand-editing files on the server.

## Conventions

- **Dependencies: always use the latest stable versions of all libraries and frameworks** (currently Spring Boot 4.x / Spring Framework 7.x on the backend). Never pin to an older major/minor without an ADR; check the registry (Maven Central, npm) or Spring Initializr for the current stable before adding or upgrading a dependency. No snapshots, RCs, or alphas in `main`.
- **Kotlin:** ktlint + detekt enforced. Constructor injection only, `val` over `var`, immutable data classes, no `!!`. Follow global Spring Boot rules (layered controller → service → repository, DTOs at the boundary, never expose entities).
- **TypeScript:** strict mode, ESLint + Prettier. Dashboard follows the global web rules (server state via TanStack Query, URL as state for filters/tabs).
- **API:** REST under `/api/v1`, consistent envelope `{ success, data, error, meta }`. Public widget endpoints (`/api/v1/consent`, `/api/v1/widget-config`) are unauthenticated but rate-limited and CORS-open; everything else requires JWT.
- **DB migrations:** Flyway, versioned, never edit an applied migration. Review with the database-reviewer agent.
- **Commits:** conventional commits (`feat:`, `fix:`, …). Branch from `main`, PR back to `main`.
- **Tests:** TDD for business logic (consent recording, billing state, policy generation, scanner classification). Testcontainers for repository/integration tests. 80% coverage target on `backend/` service layer and `widget/` core logic.

## Agent Routing

Which global agent/skill to use for which task in this repo:

| Task | Primary agent | Support |
|------|--------------|---------|
| Feature planning / breaking down epics | `planner` | `code-architect` for implementation blueprints |
| Architecture decisions, ADRs | `architect` | update `docs/ARCHITECTURE.md` via `doc-updater` |
| Backend (Kotlin/Spring) code review | `kotlin-reviewer` + `java-reviewer` (detects Spring Boot) | skills: `ecc:springboot-patterns`, `ecc:kotlin-patterns`, `ecc:jpa-patterns` |
| Backend build failures | `kotlin-build-resolver` | `java-build-resolver` for Gradle/Maven-level issues |
| Auth, JWT, billing, consent ingestion, scanner input handling | `security-reviewer` — **mandatory before merge** (global rule) | skill: `ecc:springboot-security` |
| Schema design, migrations, queue queries (SKIP LOCKED), consent-log partitioning | `database-reviewer` | skills: `ecc:postgres-patterns`, `ecc:database-migrations` |
| Dashboard (Next.js/React) review | `react-reviewer` + `typescript-reviewer` | skills: `ecc:react-patterns`, `ecc:frontend-patterns` |
| Dashboard build failures | `react-build-resolver` | `build-error-resolver` |
| Widget code (vanilla TS, size-critical) | `typescript-reviewer` | `performance-optimizer` for bundle size; skill: `ecc:vite-patterns` |
| Banner/dashboard accessibility (WCAG 2.2 — consent banners get regulator + user scrutiny) | `a11y-architect` | skill: `ecc:frontend-a11y` |
| Scanner crawl logic error handling | `silent-failure-hunter` | scanner must degrade gracefully, never hang jobs |
| New/changed business logic | `tdd-guide` (tests first) | `pr-test-analyzer` on PRs |
| E2E flows (signup → add site → scan → embed → consent) | `e2e-runner` (Playwright) | |
| Stripe / third-party API usage | `docs-lookup` (Context7 — verify current API, don't trust memory) | `security-reviewer` for webhook handling |
| Docker/compose/CI changes | `general-purpose` | skills: `ecc:docker-patterns`, `ecc:deployment-patterns` |
| Dead code / cleanup passes | `refactor-cleaner` | `code-simplifier` |
| Docs / codemaps upkeep | `doc-updater` | |
| Launch, landing page copy, positioning | `marketing-agent` | `seo-specialist` for the public site |

Run independent reviews (e.g. `kotlin-reviewer` + `security-reviewer` + `database-reviewer`) **in parallel**, not sequentially.

## Domain Glossary

- **Site** — a customer's registered domain running the widget.
- **Scan** — a Playwright crawl of a site detecting cookies/trackers, classified against the cookie signature DB.
- **Banner config** — versioned per-site widget configuration (colors, position, texts, languages, categories).
- **Consent event** — immutable record of a visitor's consent choice (audit evidence, up to 5y retention).
- **Policy document** — auto-generated cookie policy, versioned, hosted at a public URL + copyable HTML.
