# CookieKeeper dashboard — Next.js standalone output.
# Build context = dashboard/ (self-contained, no monorepo files required):
#   docker build -t complyr-dashboard dashboard/

# ---- base ------------------------------------------------------------------
FROM node:22-alpine AS base
ENV PNPM_HOME=/pnpm
ENV PATH="$PNPM_HOME:$PATH"
# corepack picks the pnpm version pinned in package.json#packageManager
RUN corepack enable pnpm

# ---- deps ------------------------------------------------------------------
FROM base AS deps
WORKDIR /app
COPY package.json pnpm-lock.yaml* ./
# pnpm 11 blocks dependency build scripts unless explicitly approved (every
# script must be listed true/false, or the install hard-fails ERR_PNPM_IGNORED_BUILDS).
# The monorepo root pnpm-workspace.yaml approves these; recreate the approval
# here because the build context (dashboard/ only) does not include it.
# Keep this list in sync with the root pnpm-workspace.yaml. @sentry/cli is
# intentionally false — the dashboard disables Sentry source-map upload (ADR-15),
# so its CLI binary is never built or invoked.
RUN printf '%s\n' \
      'allowBuilds:' \
      "  '@parcel/watcher': true" \
      "  '@sentry/cli': false" \
      "  '@swc/core': true" \
      '  esbuild: true' \
      '  sharp: true' \
      '  unrs-resolver: true' \
      > pnpm-workspace.yaml
# Dependency lockfile lives at the monorepo root (outside this build context),
# so fall back to a plain install when no local lockfile is present.
RUN if [ -f pnpm-lock.yaml ]; then \
      pnpm install --frozen-lockfile; \
    else \
      pnpm install; \
    fi

# ---- build -----------------------------------------------------------------
FROM base AS builder
WORKDIR /app
ENV NEXT_TELEMETRY_DISABLED=1
# Server-side only (never reaches the client bundle): when set, `next build`
# bakes a rewrite proxying same-origin /api/v1/* to this backend into the
# routes manifest. Local compose sets it to http://api:8080; CI/deployed
# images leave it unset — Caddy proxies /api/v1 on the app domain there.
ARG API_PROXY_TARGET
ENV API_PROXY_TARGET=$API_PROXY_TARGET
COPY --from=deps /app/node_modules ./node_modules
# Bring the lockfile + build-script approvals along so pnpm's pre-run
# dependency check passes without re-installing.
COPY --from=deps /app/pnpm-workspace.yaml /app/pnpm-lock.yaml ./
COPY . .
RUN pnpm build

# ---- runtime ---------------------------------------------------------------
FROM node:22-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
  && adduser --system --uid 1001 nextjs

COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=builder --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000

CMD ["node", "server.js"]
