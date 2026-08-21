#!/usr/bin/env bash
# =============================================================================
# deploy.sh — server-side deploy step, invoked over SSH by GitHub Actions.
#
# Usage: deploy.sh <dev|prd> <image_tag>
#   image_tag: release tag like v1.2.3
#
# Optional environment (passed by the release workflow, see `envs:` there):
#   API_DIGEST / SCANNER_DIGEST / DASHBOARD_DIGEST
#       bare `sha256:<64 hex>` values. When set, the image reference becomes
#       `repo:tag@sha256:…` and docker pulls THAT digest — so a tag repointed in
#       the registry between resolving it and running it cannot change what
#       production runs. release-prd sets them; release-dev does not (dev is
#       where the images are built, so there is nothing to promote from).
#   DEPLOY_EXPECT_HOST      override/skip the "am I on the right box" guard
#   DEPLOY_ALLOW_UNFILTERED=1  proceed without a verified egress firewall
#
# Expects /opt/cookiekeeper/<env>/ to contain:
#   compose.yml  (copied from the repo by the deploy workflow, overwritten each time)
#   .env         (environment secrets, maintained by hand on the server)
#   pgca.crt     (the database host's certificate, copied by hand — §3)
#
# Since ADR-24 dev and prd are separate machines, so <env> also says WHICH BOX
# this is allowed to run on. It is checked below: the failure mode this guards
# against is a swapped DEV_SSH_HOST/PRD_SSH_HOST secret quietly deploying the
# production tag onto dev — same command, same exit code, wrong machine.
#
# ---------------------------------------------------------------------------
# ENVIRONMENT PRECEDENCE — the thing to understand before editing this file.
#
# Compose resolves `${VAR}` from the SHELL ENVIRONMENT FIRST and only then from
# --env-file. That is the opposite of what "the file the script just wrote wins"
# would suggest, and getting it wrong is silent:
#
#   .env.deploy says API_DIGEST=@sha256:abc   (normalised, with the '@')
#   the shell says API_DIGEST=sha256:abc      (bare, as ssh-action exports it)
#   → compose renders  repo:v1.2.3sha256:abc  — no '@', an invalid reference
#
# So every value this script controls is EXPORTED, deliberately using that
# precedence rather than fighting it. .env.deploy is still written, because the
# `dc()` troubleshooting helper in DEPLOYMENT.md §15 and a human running compose
# by hand have no exports — but the export is what actually binds during a
# deploy. The two are always written together by apply_deploy_env().
#
# The same precedence is what lets `export COMPOSE_PROFILES=` guarantee that prd
# never starts Mailpit, even if someone copies dev's .env onto the prd box.
# =============================================================================
set -euo pipefail

usage() {
  echo "Usage: $0 <dev|prd> <image_tag>" >&2
  exit 64
}

[[ $# -eq 2 ]] || usage
ENV_NAME="$1"
IMAGE_TAG="$2"
[[ "$ENV_NAME" == "dev" || "$ENV_NAME" == "prd" ]] || usage
[[ -n "$IMAGE_TAG" ]] || usage

DEPLOY_DIR="/opt/cookiekeeper/${ENV_NAME}"
COMPOSE_FILE="compose.yml"
PROJECT="cookiekeeper-${ENV_NAME}"

# Backstop only. It is NOT the normal failure budget: compose aborts as soon as a
# container is marked *unhealthy*, so what actually decides a failing deploy is
# each service's own healthcheck budget (start_period + retries × interval) —
# ~165s for api, ~105s for dashboard, both inside this. This ceiling exists for
# the one case those cannot cover: a container stuck in `starting` forever, which
# would otherwise hold the release workflow open indefinitely.
WAIT_TIMEOUT_SECONDS=240

# The container subnet, DERIVED from <env> rather than read from .env. One file
# now serves both environments (infra/compose.yml), so this is where the two
# differ — and it must never be a hand-editable value: the egress firewall
# (ADR-18) writes its RETURN exemptions per source subnet, so a subnet it does
# not know about is a subnet whose legitimate traffic is not exempted.
#
# These two addresses appear in exactly two places: here, and the
# MESH_SUBNETS/DB_TARGETS defaults in infra/scripts/egress-firewall.sh. Change one
# and you must change the other — the check further down enforces that on the box.
case "$ENV_NAME" in
  dev) APP_SUBNET="10.31.10.0/24" ;;
  prd) APP_SUBNET="10.31.20.0/24" ;;
esac

# Right environment, right machine. DEPLOY_EXPECT_HOST overrides the derived name
# if the servers are ever renamed in platform/variables.tf; setting it to the
# empty string skips the check for a one-off recovery on a rebuilt box.
EXPECT_HOST="${DEPLOY_EXPECT_HOST-cookiekeeper-${ENV_NAME}-app}"
if [[ -n "$EXPECT_HOST" && "$(hostname -s)" != "$EXPECT_HOST" ]]; then
  echo "!! refusing to deploy '${ENV_NAME}' on $(hostname -s) — this is not ${EXPECT_HOST}" >&2
  exit 1
fi

cd "$DEPLOY_DIR"

for required in "$COMPOSE_FILE" .env pgca.crt; do
  [[ -e "$required" ]] || {
    echo "!! ${DEPLOY_DIR}/${required} is missing — see docs/DEPLOYMENT.md §3." >&2
    exit 1
  }
done

# Read a key out of a KEY=VALUE file. sed exits 0 on no match, so an absent key
# yields the empty string rather than tripping `set -e` — the callers below all
# want "absent" to be a value they can test, not a crash.
file_value() {
  [[ -r "$1" ]] || return 0
  sed -n "s/^$2=//p" "$1" | tail -1 | tr -d "\"'[:space:]"
}

# ---- egress firewall (ADR-18) ----------------------------------------------
# Fail before touching anything if the firewall on this box does not know the
# subnet we are about to (re)create. ARCHITECTURE.md calls ADR-18 a blocking
# deploy requirement, so an unverifiable firewall is an error, not a warning: the
# scanner executes untrusted third-party JavaScript from attacker-chosen domains,
# and unfiltered that reaches 169.254.169.254 and the database host's sshd.
#
# HONEST LIMITATION: this reads the installed SCRIPT, not the kernel's rules —
# deploy.sh runs unprivileged and cannot call `iptables -S`. It catches the
# renumbering mistake it is aimed at (config drift between this file and the
# firewall's subnet lists). It does NOT catch a masked unit or a flushed chain;
# `cookiekeeper-egress-firewall verify` on the box, run as root, is what proves
# the rules are actually loaded.
FIREWALL_SCRIPT="/usr/local/sbin/cookiekeeper-egress-firewall"
if [[ -r "$FIREWALL_SCRIPT" ]]; then
  # -F: the subnet contains '.', which is a wildcard in a regex and would let a
  # near-miss like 10a31a10a0/24 satisfy the check.
  if ! grep -qF -- "$APP_SUBNET" "$FIREWALL_SCRIPT"; then
    echo "!! ${APP_SUBNET} is not in ${FIREWALL_SCRIPT} — its traffic would not be exempted." >&2
    echo "!! Update MESH_SUBNETS and DB_TARGETS there (ADR-18), reinstall, and re-run." >&2
    exit 1
  fi
elif [[ "${DEPLOY_ALLOW_UNFILTERED-}" == "1" ]]; then
  echo "!! WARNING: ${FIREWALL_SCRIPT} not readable and DEPLOY_ALLOW_UNFILTERED=1." >&2
  echo "!! Deploying with UNVERIFIED container egress filtering. Install it (server-setup.md §4)." >&2
else
  echo "!! ${FIREWALL_SCRIPT} is not readable — cannot confirm container egress is filtered." >&2
  echo "!! Install it (server-setup.md §4), or re-run with DEPLOY_ALLOW_UNFILTERED=1 to accept" >&2
  echo "!! running the scanner's untrusted-JS workload unfiltered." >&2
  exit 1
fi

# ---- constraint #4: dev mail must never reach a real person ------------------
# The old compose.prd.yml had no mailpit service at all, which made this
# structurally impossible. With one shared file behind a compose profile, the
# guarantee has to be asserted instead. Checked against the KEY only; the value
# of MAIL_PROVIDER is not a secret but nothing else from .env is ever read here.
MAIL_PROVIDER_SET="$(file_value .env MAIL_PROVIDER)"
if [[ "$ENV_NAME" == "prd" && "$MAIL_PROVIDER_SET" != "brevo" ]]; then
  echo "!! prd has MAIL_PROVIDER='${MAIL_PROVIDER_SET:-<unset>}', expected 'brevo'." >&2
  echo "!! prd is the only environment that mails real people, and Mailpit is not running" >&2
  echo "!! here — anything else silently sends production mail nowhere." >&2
  exit 1
fi
if [[ "$ENV_NAME" == "dev" && "$MAIL_PROVIDER_SET" == "brevo" ]]; then
  echo "!! dev has MAIL_PROVIDER=brevo — a test signup would mail a real person (constraint #4)." >&2
  exit 1
fi

# A mailpit container already running in the prd project cannot be removed by
# `up --remove-orphans`: compose leaves profile-disabled services that are
# already up strictly alone. Fail loudly — if this exists, prd mail may already
# have been swallowed, which is a question for a human and not for a retry.
if [[ "$ENV_NAME" == "prd" ]]; then
  if [[ -n "$(docker ps -aq \
        --filter "label=com.docker.compose.project=${PROJECT}" \
        --filter "label=com.docker.compose.service=mailpit")" ]]; then
    echo "!! a mailpit container exists in project ${PROJECT}. It must never run in prd." >&2
    echo "!! Check whether production mail was captured, then remove it:" >&2
    echo "!!   docker rm -f \$(docker ps -aq -f label=com.docker.compose.project=${PROJECT} \\" >&2
    echo "!!                                 -f label=com.docker.compose.service=mailpit)" >&2
    exit 1
  fi
fi

# ---- image pins --------------------------------------------------------------
# Normalise an optional digest pin into the `@sha256:…` suffix compose expects.
# Validated in the main shell, NOT inside a command substitution — an `exit` there
# would only end the subshell and the deploy would carry on with an empty pin.
require_digest_suffix() {
  local name="$1" value="${2-}"
  if [[ -z "$value" ]]; then
    printf ''
    return 0
  fi
  if [[ ! "$value" =~ ^sha256:[0-9a-f]{64}$ ]]; then
    echo "!! malformed ${name} '${value}' — expected sha256:<64 hex>" >&2
    return 1
  fi
  printf '@%s' "$value"
}

API_PIN="$(require_digest_suffix API_DIGEST "${API_DIGEST-}")"
SCANNER_PIN="$(require_digest_suffix SCANNER_DIGEST "${SCANNER_DIGEST-}")"
DASHBOARD_PIN="$(require_digest_suffix DASHBOARD_DIGEST "${DASHBOARD_DIGEST-}")"

# ---- what compose will interpolate ------------------------------------------
# Exported AND written to .env.deploy — see the precedence note in the header.
# The secrets .env is never rewritten by automation; .env.deploy is machine-owned
# and safe to clobber, and holds no secrets.
#
# COMPOSE_PROFILES is set explicitly in BOTH directions. Empty on prd is not a
# no-op: it overrides any COMPOSE_PROFILES a hand-edited .env might carry, which
# is the only remaining way Mailpit could start in production.
apply_deploy_env() {
  export IMAGE_TAG="$1"
  export API_DIGEST="$2"
  export SCANNER_DIGEST="$3"
  export DASHBOARD_DIGEST="$4"
  export ENV_NAME APP_SUBNET
  if [[ "$ENV_NAME" == "dev" ]]; then
    export COMPOSE_PROFILES="dev"
  else
    export COMPOSE_PROFILES=""
  fi
  cat > .env.deploy <<EOF
IMAGE_TAG=${IMAGE_TAG}
ENV_NAME=${ENV_NAME}
APP_SUBNET=${APP_SUBNET}
API_DIGEST=${API_DIGEST}
SCANNER_DIGEST=${SCANNER_DIGEST}
DASHBOARD_DIGEST=${DASHBOARD_DIGEST}
EOF
}

apply_deploy_env "$IMAGE_TAG" "$API_PIN" "$SCANNER_PIN" "$DASHBOARD_PIN"

COMPOSE=(docker compose --project-name "$PROJECT" --file "$COMPOSE_FILE"
         --env-file .env --env-file .env.deploy)

# `up --wait` is not trustworthy enough to be the ONLY gate. It reliably returns
# non-zero when a container goes `unhealthy`, but on the --wait-timeout path — a
# container stuck in `starting`, e.g. a JVM wedged on a slow migration or a
# hanging DB connect — it intermittently returns 0 (observed 3 times in 25 runs).
# That is precisely the failure this whole change exists to catch, so re-assert
# the state from the daemon instead of trusting the exit code.
#
# Asked of docker directly rather than `compose ps --format json` to avoid needing
# jq on the server. A service with no healthcheck (scanner) reports `none` and is
# judged on `running` alone, matching --wait's own semantics.
assert_stack_healthy() {
  local ids cid name status health failed=0
  ids="$("$@" ps -q)" || return 1
  if [[ -z "$ids" ]]; then
    echo "!! ${PROJECT} has no running containers" >&2
    return 1
  fi
  while read -r cid; do
    [[ -n "$cid" ]] || continue
    read -r name status health < <(docker inspect --format \
      '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
      "$cid")
    if [[ "$status" != "running" ]]; then
      echo "!! ${name}: state=${status}" >&2
      failed=1
    elif [[ "$health" != "none" && "$health" != "healthy" ]]; then
      echo "!! ${name}: health=${health}" >&2
      failed=1
    fi
  done <<< "$ids"
  return "$failed"
}

echo "==> Deploying ${PROJECT} @ ${IMAGE_TAG}"
"${COMPOSE[@]}" pull

# --wait blocks until every service with a healthcheck reports healthy, so the
# workflow's smoke test is a second opinion rather than the first signal that the
# release is broken. Services without one (scanner) count as ready when running.
if "${COMPOSE[@]}" up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" \
   && assert_stack_healthy "${COMPOSE[@]}"; then
  # ONLY a deploy that actually came up healthy becomes the rollback target.
  # Snapshotting on entry instead would let a release that failed at `pull` — or
  # anywhere before `up` — be recorded as the last known-good one, and the next
  # rollback would aim at a tag that never ran.
  cp .env.deploy .env.deploy.lastgood
  # The compose file too: the deploy workflow overwrote compose.yml before this
  # script ran, so rolling back images alone would run the OLD containers against
  # the NEW file — new healthchecks, new limits, new required variables. That
  # combination has never been tested, and an incident is a bad time to find out.
  cp "$COMPOSE_FILE" compose.yml.lastgood
  docker image prune -af --filter "until=168h" >/dev/null
  echo "==> ${PROJECT} deployed at tag ${IMAGE_TAG}"
  exit 0
fi

# ---- failed to come up healthy ---------------------------------------------
echo "!! ${PROJECT} did not become healthy at ${IMAGE_TAG}" >&2
"${COMPOSE[@]}" ps >&2 || true

# Container logs are NOT echoed to the CI job log. Spring Boot's binding failure
# analyzer prints `Property: … Value: …` for a malformed setting, so a bad
# STRIPE_*/BREVO_API_KEY/JWT_SECRET/DB_PASSWORD in the server's .env would be
# printed verbatim — and GitHub cannot mask a value it has never seen. On a
# timeout rather than a crash the same dump can carry request logs (constraint
# #4). Keep it on the box, 0600, and print only where it is.
FAILURE_LOG="deploy-failure-$(date -u +%Y%m%dT%H%M%SZ).log"
( umask 077; "${COMPOSE[@]}" logs --tail 200 > "$FAILURE_LOG" 2>&1 ) || true
echo "!! container logs: ${DEPLOY_DIR}/${FAILURE_LOG} (0600, deliberately not exported to CI)" >&2

PREVIOUS_TAG="$(file_value .env.deploy.lastgood IMAGE_TAG)"
PREVIOUS_API="$(file_value .env.deploy.lastgood API_DIGEST)"
PREVIOUS_SCANNER="$(file_value .env.deploy.lastgood SCANNER_DIGEST)"
PREVIOUS_DASHBOARD="$(file_value .env.deploy.lastgood DASHBOARD_DIGEST)"

if [[ -z "$PREVIOUS_TAG" || "$PREVIOUS_TAG" == "$IMAGE_TAG" ]]; then
  echo "!! no distinct last-known-good deploy to roll back to — leaving the stack for inspection." >&2
  exit 1
fi

# Refusing to roll prd back unpinned is the point of the whole digest mechanism:
# an empty pin means resolving the tag by name against GHCR, which is exactly the
# repoint window release-prd exists to close. The first failure after this script
# is installed hits it (the pre-existing .env.deploy had no digest keys), and an
# incident is precisely when you do not want to quietly relax the guarantee.
if [[ "$ENV_NAME" == "prd" && -z "$PREVIOUS_API" ]]; then
  echo "!! last-known-good ${PREVIOUS_TAG} carries no digest pins; refusing to roll prd back" >&2
  echo "!! to a tag resolved by name. Promote a known-good tag with release-prd instead." >&2
  exit 1
fi

# ROLLBACK IS THE APPLICATION ONLY. Flyway migrations are forward-only and ran on
# the new image's boot; they are NOT undone here. This recovers the common case —
# a build that will not start — and for a bad migration it buys a running older
# app against an already-migrated schema, which may itself be wrong. Check the
# migration before trusting a green rollback.
echo "==> Rolling back ${PROJECT} to ${PREVIOUS_TAG}" >&2
# apply_deploy_env exports IMAGE_TAG, so it is about to stop naming the release that failed.
FAILED_TAG="$IMAGE_TAG"
apply_deploy_env "$PREVIOUS_TAG" "$PREVIOUS_API" "$PREVIOUS_SCANNER" "$PREVIOUS_DASHBOARD"

ROLLBACK_FILE="$COMPOSE_FILE"
[[ -f compose.yml.lastgood ]] && ROLLBACK_FILE="compose.yml.lastgood"
COMPOSE_ROLLBACK=(docker compose --project-name "$PROJECT" --file "$ROLLBACK_FILE"
                  --env-file .env --env-file .env.deploy)

if "${COMPOSE_ROLLBACK[@]}" up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT_SECONDS" \
   && assert_stack_healthy "${COMPOSE_ROLLBACK[@]}"; then
  echo "!! rolled back to ${PREVIOUS_TAG} (using ${ROLLBACK_FILE}). ${FAILED_TAG} is NOT deployed." >&2
else
  echo "!! ROLLBACK ALSO FAILED — ${PROJECT} is down. Manual intervention required." >&2
fi
exit 1
