# CookieKeeper

GDPR/cookie consent management micro-SaaS for small European businesses: an
embeddable consent banner, automated cookie scanning, generated cookie
policies, and an audit-grade consent log. EU-hosted end to end.

- Architecture and rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Accounts, keys, servers and pipelines: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)
- Working conventions and agent routing: [CLAUDE.md](CLAUDE.md)

## Monorepo layout

| Path | What |
|------|------|
| `backend/` | Spring Boot 4 (Kotlin, JDK 21) — REST API, auth, billing, consent ingestion, policy generation; scanner worker via Spring profile `scanner` |
| `dashboard/` | Next.js (App Router, latest stable) customer dashboard (TypeScript, Tailwind, shadcn/ui) |
| `widget/` | Embeddable consent banner — vanilla TS, Vite IIFE build, zero runtime deps, ≤20KB gzipped |
| `infra/` | Docker Compose stacks, Terraform (`infra/terraform/`), Caddy config, deploy/backup scripts |
| `.github/workflows/` | validate → main → release-dev → release-prd |
| `docs/` | Architecture, ADRs, runbooks |

## Quickstart (on your machine)

Prerequisites: Docker, Node 22, pnpm 11. A JDK is only needed if you run the
backend outside Docker.

```bash
cp .env.example .env          # fill in values; the defaults are workstation-ready
docker compose -f infra/compose.workstation.yml up
```

- Dashboard: http://localhost:3000
- API: http://localhost:8080 (health: `/actuator/health`)
- Mailpit (catches all outgoing email): http://localhost:8025
- Scanner is opt-in: add `--profile scanner`

A workstation is **not** a third environment. It runs the same `dev` Spring
profile the deployed dev environment runs; the only difference is where the
configuration comes from — your `.env` file here, `/opt/cookiekeeper/dev/.env`
there — plus `COOKIE_SECURE=false`, because a browser drops a `Secure` cookie
served over plain `http://localhost`.

Per-module development:

```bash
cd widget && pnpm install && pnpm dev        # widget dev harness
cd widget && pnpm test && pnpm build && pnpm size   # 20KB gzip gate
cd dashboard && pnpm dev
cd backend && ./gradlew bootRun              # needs local JDK 21
```

## Environments

There are exactly two, and each owns **two** Hetzner CX22 servers in
Falkenstein — an application host running the containers and a dedicated
Postgres host, joined by a private network only they are on (ADR-24). The two
environments share nothing: no machine, no network, no credential.

| Env | Machines | Compose project | Deployed by |
|-----|----------|-----------------|-------------|
| dev | `cookiekeeper-dev-app` + `cookiekeeper-dev-db` | `cookiekeeper-dev` | `release-dev` — dispatched by hand on `main` |
| prd | `cookiekeeper-prd-app` + `cookiekeeper-prd-db` | `cookiekeeper-prd` | `release-prd` — dispatched by hand on a `vX.Y.Z` tag |

The database hosts run Postgres as a system package with no Docker on them at
all, and are not in DNS, not reachable by CI, and not part of any deploy. Only
`infra/compose.workstation.yml` still runs Postgres in a container.

Merging to `main` builds and tests but never deploys. Releasing is always a
decision someone makes, never a consequence of merging.

## Pipelines

| Workflow | Trigger | Does |
|----------|---------|------|
| `validate` | PR, push to any non-`main` branch | lint, compile, `terraform fmt`/`validate` |
| `main` | push to `main` | the above + tests + builds + images tagged `sha-…` |
| `release-dev` | manual, on `main` | tests, version bump, tag, `terraform apply` (dev), deploy to dev |
| `release-prd` | manual, on a `vX.Y.Z` tag | tests, `terraform apply` (prd), promote the **same images** to prd |

Full setup — third-party accounts, keys, Terraform, Caddy, the CDN — is in
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md). The one-time server runbook is
[infra/scripts/server-setup.md](infra/scripts/server-setup.md).
