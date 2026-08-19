#!/usr/bin/env bash
# =============================================================================
# deploy.sh — server-side deploy step, invoked over SSH by GitHub Actions.
#
# Usage: deploy.sh <dev|prd> <image_tag>
#   image_tag: git sha (dev) or release tag like v1.2.3 (prd)
#
# Expects /opt/cookiekeeper/<env>/ to contain:
#   compose.<env>.yml (rsynced from the repo by the deploy workflow)
#   .env              (environment secrets, maintained by hand on the server)
#   pgca.crt          (the database host's certificate, copied by hand — §3)
#
# Since ADR-24 dev and prd are separate machines, so <env> also says WHICH BOX
# this is allowed to run on. It is checked below: the failure mode this guards
# against is a swapped DEV_SSH_HOST/PRD_SSH_HOST secret quietly deploying the
# production tag onto dev — same command, same exit code, wrong machine.
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
COMPOSE_FILE="compose.${ENV_NAME}.yml"
PROJECT="cookiekeeper-${ENV_NAME}"

# Right environment, right machine. DEPLOY_EXPECT_HOST overrides the derived name
# if the servers are ever renamed in platform/variables.tf; setting it to the
# empty string skips the check for a one-off recovery on a rebuilt box.
EXPECT_HOST="${DEPLOY_EXPECT_HOST-cookiekeeper-${ENV_NAME}-app}"
if [[ -n "$EXPECT_HOST" && "$(hostname -s)" != "$EXPECT_HOST" ]]; then
  echo "!! refusing to deploy '${ENV_NAME}' on $(hostname -s) — this is not ${EXPECT_HOST}" >&2
  exit 1
fi

cd "$DEPLOY_DIR"

# Record the tag being deployed. compose reads .env + .env.deploy; keeping the
# tag in its own file means the secrets .env is never rewritten by automation.
echo "IMAGE_TAG=${IMAGE_TAG}" > .env.deploy

echo "==> Deploying ${PROJECT} @ ${IMAGE_TAG}"
docker compose \
  --project-name "$PROJECT" \
  --file "$COMPOSE_FILE" \
  --env-file .env \
  --env-file .env.deploy \
  pull

docker compose \
  --project-name "$PROJECT" \
  --file "$COMPOSE_FILE" \
  --env-file .env \
  --env-file .env.deploy \
  up -d --remove-orphans

# Prune images no longer referenced by any container (keeps the small disk sane)
docker image prune -af --filter "until=168h" >/dev/null

echo "==> ${PROJECT} deployed at tag ${IMAGE_TAG}"
