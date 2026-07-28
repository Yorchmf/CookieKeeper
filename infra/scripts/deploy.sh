#!/usr/bin/env bash
# =============================================================================
# deploy.sh — server-side deploy step, invoked over SSH by GitHub Actions.
#
# Usage: deploy.sh <dev|prd> <image_tag>
#   image_tag: git sha (dev) or release tag like v1.2.3 (prd)
#
# Expects /opt/complyr/<env>/ to contain:
#   compose.<env>.yml (rsynced from the repo by the deploy workflow)
#   .env              (environment secrets, maintained by hand on the server)
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

DEPLOY_DIR="/opt/complyr/${ENV_NAME}"
COMPOSE_FILE="compose.${ENV_NAME}.yml"
PROJECT="complyr-${ENV_NAME}"

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
