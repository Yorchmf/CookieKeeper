# Running Complyr

How to run each part of the stack locally (from IntelliJ and from the command line, both with and without Docker) and how the CI/CD pipelines work.

Before anything: copy the root env file once.

```bash
cp .env.example .env
cp dashboard/.env.example dashboard/.env.local
```

`.env` is read by `infra/compose.local.yml` and by the backend when you run it outside Docker. Never commit either file (both are gitignored).

---

## 1. Backend (`backend/`, Spring Boot 4, Kotlin)

The backend always needs a Postgres instance to talk to — pick one:

```bash
docker compose -f infra/compose.local.yml up postgres mailpit -d
```

This starts just Postgres (`localhost:5432`, db/user/pass all `complyr`) and Mailpit (SMTP `localhost:1025`, web UI `http://localhost:8025`) without building/running the api or dashboard containers — the fastest way to get a dependency-only sandbox for IDE-driven development.

### 1a. From IntelliJ IDEA

1. **Open** the `backend/` folder as a Gradle project (IntelliJ auto-imports via `settings.gradle.kts`). Wait for Gradle sync to finish.
2. Start Postgres + Mailpit as above if you haven't already.
3. Open `ComplyrBackendApplication.kt` and create a run configuration from the gutter icon, or go to **Run → Edit Configurations → + → Spring Boot** and point it at `ComplyrBackendApplication`.
4. In that run configuration:
   - **Active profiles:** `local`
   - **Environment variables:**
     ```
     DB_URL=jdbc:postgresql://localhost:5432/complyr
     DB_USER=complyr
     DB_PASSWORD=complyr
     SMTP_HOST=localhost
     SMTP_PORT=1025
     ```
     (`application.yml` defaults `DB_URL`/`DB_USER`/`DB_PASSWORD` to these same local values, so you can usually skip them — set them explicitly if you changed the Postgres credentials.)
5. Run or Debug the configuration. The API comes up on `http://localhost:8080`; check `http://localhost:8080/actuator/health`.
6. **ktlint/detekt from IntelliJ:** Run → Edit Configurations → + → Gradle → task `ktlintCheck detekt` (or run them from the Gradle tool window under `backend → Tasks → verification`).

To run the **scanner** worker instead, duplicate the run configuration, set **Active profiles** to `scanner` (no web server starts — see `application-scanner.yml`) and keep the same DB env vars.

### 1b. From the command line

**Without Docker** (needs Postgres reachable — start it via the compose command above, or point at any local Postgres 16):

```bash
cd backend
DB_URL=jdbc:postgresql://localhost:5432/complyr \
DB_USER=complyr \
DB_PASSWORD=complyr \
SMTP_HOST=localhost SMTP_PORT=1025 \
./gradlew bootRun --args='--spring.profiles.active=local'
```

Build + test + lint without running the app:

```bash
cd backend
./gradlew build          # compile + unit/integration tests (Testcontainers spins up its own Postgres)
./gradlew ktlintCheck detekt
```

**With Docker** (build the actual production image and run it):

```bash
docker build -t complyr-backend -f backend/Dockerfile backend/
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=api,local \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/complyr \
  -e DB_USER=complyr -e DB_PASSWORD=complyr \
  complyr-backend
```

(`host.docker.internal` reaches a Postgres running on your host machine, e.g. from the compose command above, from inside the container. On Linux add `--add-host=host.docker.internal:host-gateway` if it doesn't resolve.)

Scanner image, same idea:

```bash
docker build -t complyr-scanner -f backend/Dockerfile.scanner backend/
docker run --rm \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/complyr \
  -e DB_USER=complyr -e DB_PASSWORD=complyr \
  complyr-scanner
```

---

## 2. Dashboard (`dashboard/`, Next.js 16)

### 2a. From IntelliJ IDEA

IntelliJ doesn't need a special run configuration for Next.js — use the built-in terminal (**View → Tool Windows → Terminal**) and run the pnpm commands from §2b, or add an **npm** run configuration (**Run → Edit Configurations → + → npm**) with:
- **package.json:** `dashboard/package.json`
- **Command:** `run`, **Scripts:** `dev`

WebStorm/IntelliJ Ultimate will also give you inline TypeScript/ESLint checking automatically once the folder is open with `dashboard/` marked as a project root (right-click `dashboard/` → Mark Directory as → Sources Root, if it isn't picked up already).

### 2b. From the command line

**Without Docker:**

```bash
cd dashboard
pnpm install
pnpm dev
```

Dashboard runs at `http://localhost:3000`. It reads `NEXT_PUBLIC_API_URL` from `dashboard/.env.local` (defaults to `http://localhost:8080` — point it at your locally running backend from §1).

Lint/build:

```bash
cd dashboard
pnpm lint
pnpm build && pnpm start   # production build + serve
```

**With Docker:**

```bash
docker build -t complyr-dashboard \
  --build-arg NEXT_PUBLIC_API_URL=http://localhost:8080 \
  dashboard/
docker run --rm -p 3000:3000 complyr-dashboard
```

`NEXT_PUBLIC_API_URL` is baked in at build time (Next.js inlines `NEXT_PUBLIC_*` vars into the client bundle) — pass it as a `--build-arg`, not a runtime `-e`. In deployed dev/prd this is unset on purpose; the dashboard calls same-origin `/api/v1/*` and Caddy proxies it (see `docs/ARCHITECTURE.md` §6).

---

## 3. Widget (`widget/`, vanilla TypeScript)

### 3a. From IntelliJ IDEA

Same as the dashboard — use the terminal or an npm run configuration pointed at `widget/package.json` with script `dev`.

### 3b. From the command line

**Without Docker** (Vite dev server with hot reload — this is the normal way to work on the widget, it isn't containerized in practice):

```bash
cd widget
pnpm install
pnpm dev
```

Open the printed local URL (Vite's dev harness `index.html` simulates a customer embedding the script). Because the widget derives its API/CDN base URL from its own `<script src>` origin, the `localhost` dev harness automatically talks to itself — point `API_BASE`-dependent calls at a real backend by running the backend from §1 on `localhost:8080` alongside it.

Test, build, and check the size budget:

```bash
cd widget
pnpm test        # vitest
pnpm build        # emits dist/v1.js
pnpm size         # fails if dist/v1.js exceeds the 20KB gzipped budget
```

There is no Dockerfile for the widget — it's a static asset (`dist/v1.js`), not a service. It ships to the CDN directory by the deploy pipeline (§4), not as a container.

---

## 4. Full local stack (Docker Compose)

To run everything together the way it runs in dev/prd (minus Caddy):

```bash
docker compose -f infra/compose.local.yml up --build
```

This builds and starts Postgres, Mailpit, the API (`http://localhost:8080`), and the dashboard (`http://localhost:3000`), wired to talk to each other. The scanner is **opt-in** (heavy Playwright image) — include it explicitly:

```bash
docker compose -f infra/compose.local.yml --profile scanner up --build
```

Useful variants:

```bash
docker compose -f infra/compose.local.yml up -d          # detached
docker compose -f infra/compose.local.yml logs -f api    # tail one service
docker compose -f infra/compose.local.yml down           # stop (add -v to also wipe the Postgres volume)
```

---

## 5. Running the CI/CD pipelines

All three pipelines live in `.github/workflows/` and run on GitHub Actions — there is nothing to install locally to trigger them.

| Workflow | File | Triggers | What it does |
|---|---|---|---|
| **CI** | `ci.yml` | Every pull request and every push to `main` | Runs the backend build/lint/test, dashboard lint/build, and widget test/build/size-gate — only the modules whose files changed (paths-filtered) |
| **Deploy dev** | `deploy-dev.yml` | Push to `main` (i.e. right after a PR merges) | Builds all 3 images, pushes to GHCR, uploads the widget bundle to the dev CDN, SSHes into the VPS and runs `deploy.sh dev <sha>`, then smoke-tests the dev domains (non-blocking until the server exists) |
| **Deploy prd** | `deploy-prd.yml` | Pushing a git tag matching `v*` (e.g. `v1.0.0`) | Promotes the exact dev-tested image digests to the release tag (no rebuild), behind a **manual approval gate** (GitHub Environment `production`), deploys to prd, then runs a blocking smoke test |

### Triggering them

- **CI** runs automatically — just open a PR or push to `main`. Nothing to do manually.
- **Deploy dev** runs automatically on every merge to `main`. Nothing to do manually.
- **Deploy prd** requires you to cut a release tag once you're happy with what's on dev:

  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

  This queues the workflow, which then **pauses for approval** (configure the `production` GitHub Environment with a required reviewer under **Settings → Environments** — see `infra/scripts/server-setup.md`). Approve the run in the **Actions** tab to let it proceed.

### Watching a run

```bash
gh run list --workflow=ci.yml
gh run watch                       # watches the most recent run
gh workflow view "Deploy prd"
```

### Required repository secrets

Configure these once under **Settings → Secrets and variables → Actions** before the deploy workflows can reach the VPS:

| Secret | Purpose |
|---|---|
| `SSH_HOST` | VPS hostname/IP |
| `SSH_USER` | Deploy-only SSH user |
| `SSH_KEY` | Private key for that user (see `infra/scripts/server-setup.md` for how it's provisioned) |

`GITHUB_TOKEN` (GHCR push) is provided automatically by Actions — no setup needed.

### Running pipeline steps locally before pushing

You don't need to push to see if CI will pass — run the same commands it runs:

```bash
# what ci.yml runs for the backend
cd backend && ./gradlew build ktlintCheck detekt

# what ci.yml runs for the dashboard
cd dashboard && pnpm lint && pnpm build

# what ci.yml runs for the widget
cd widget && pnpm test && pnpm build && pnpm size
```

If you have [`act`](https://github.com/nektos/act) installed, you can also dry-run a workflow file directly against your local Docker daemon (`act pull_request -W .github/workflows/ci.yml`), though the Testcontainers-based backend tests need Docker-in-Docker and are easier to just run with the Gradle command above.
