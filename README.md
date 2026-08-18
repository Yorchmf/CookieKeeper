# CookieKeeper

GDPR/cookie consent management micro-SaaS for small European businesses: an
embeddable consent banner, automated cookie scanning, generated cookie
policies, and an audit-grade consent log. EU-hosted end to end.

- Architecture and rationale: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Working conventions and agent routing: [CLAUDE.md](CLAUDE.md)
- Running the app (IntelliJ, CLI, with/without Docker) and the pipelines: [docs/RUNNING.md](docs/RUNNING.md)

## Monorepo layout

| Path | What |
|------|------|
| `backend/` | Spring Boot 4 (Kotlin, JDK 21) — REST API, auth, billing, consent ingestion, policy generation; scanner worker via Spring profile `scanner` |
| `dashboard/` | Next.js (App Router, latest stable) customer dashboard (TypeScript, Tailwind, shadcn/ui) |
| `widget/` | Embeddable consent banner — vanilla TS, Vite IIFE build, zero runtime deps, ≤20KB gzipped |
| `infra/` | Docker Compose stacks (local/dev/prd), Caddy config, deploy/backup scripts |
| `.github/workflows/` | CI + deploy pipelines (GHCR images → SSH deploy to Hetzner VPS) |
| `docs/` | Architecture, ADRs, runbooks |

## Quickstart (local)

Prerequisites: Docker, Node 22, pnpm 11. JDK not required for the Docker path
— see [docs/RUNNING.md](docs/RUNNING.md) for running each module from
IntelliJ or the CLI, with or without Docker.

```bash
cp .env.example .env          # fill in local values (defaults mostly work)
docker compose -f infra/compose.local.yml up
```

- Dashboard: http://localhost:3000
- API: http://localhost:8080 (health: `/actuator/health`)
- Mailpit (catches all local email): http://localhost:8025
- Scanner is opt-in locally: add `--profile scanner`

Per-module development:

```bash
cd widget && pnpm install && pnpm dev        # widget dev harness
cd widget && pnpm test && pnpm build && pnpm size   # 20KB gzip gate
cd dashboard && pnpm dev
cd backend && ./gradlew bootRun              # needs local JDK 21
```

## Environments

| Env | Where | Deploy trigger |
|-----|-------|----------------|
| local | Docker Compose on your machine | manual |
| dev | Hetzner VPS, compose project `cookiekeeper-dev` | auto on merge to `main` |
| prd | Same VPS, compose project `cookiekeeper-prd` | manual approval on tag `v*` |

See [infra/scripts/server-setup.md](infra/scripts/server-setup.md) for the
one-time server runbook.
