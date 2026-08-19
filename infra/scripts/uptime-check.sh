#!/usr/bin/env bash
# =============================================================================
# uptime-check.sh — on-box dead-man's-switch health probe (see monitoring/uptime.md).
#
# Curls the API health, dashboard, and widget CDN from the APP HOST it runs on
# (one instance per app host since ADR-24, each with its own HEARTBEAT_URL — a
# shared heartbeat would let a healthy dev keep production's alarm quiet). If ALL are
# healthy it pings HEARTBEAT_URL; on ANY failure it logs which check failed and
# does NOT ping, so the external heartbeat monitor alerts after its grace period.
# This backstops the external synthetic checks and catches app-degraded states a
# bare HTTP 200 would hide (e.g. API up but DB down → health reports DOWN — which
# is also how a dead database HOST surfaces, since it is not probed directly).
#
# Runs every minute from cron, sourcing /opt/cookiekeeper/monitoring.env for config.
# =============================================================================
set -uo pipefail   # NOT -e: a failed check must be handled, not abort the script

HEARTBEAT_URL="${HEARTBEAT_URL:-}"
API_HEALTH_URL="${API_HEALTH_URL:-http://localhost:8080/actuator/health}"
DASHBOARD_URL="${DASHBOARD_URL:-http://localhost:3000}"
WIDGET_URL="${WIDGET_URL:-http://localhost:8081/v1.js}"
TIMEOUT="${UPTIME_CHECK_TIMEOUT:-10}"

ok=1

# Assert an HTTP GET returns 2x/3xx AND (optionally) the body contains a marker.
check() {
  local name="$1" url="$2" want_body="${3:-}"
  local body status
  # -s silent, -S show errors, -L follow redirects, -m timeout; capture body +
  # trailing HTTP code so we can assert both without a second request.
  body="$(curl -sS -L -m "$TIMEOUT" -w '\n%{http_code}' "$url" 2>/dev/null)" || {
    echo "!! ${name} DOWN — curl failed (${url})" >&2; ok=0; return
  }
  status="${body##*$'\n'}"
  body="${body%$'\n'*}"
  if [[ ! "$status" =~ ^[23][0-9][0-9]$ ]]; then
    echo "!! ${name} DOWN — HTTP ${status} (${url})" >&2; ok=0; return
  fi
  if [[ -n "$want_body" && "$body" != *"$want_body"* ]]; then
    echo "!! ${name} DEGRADED — missing '${want_body}' in body (${url})" >&2; ok=0; return
  fi
}

# Health must report UP in the body — a 200 with status DOWN (DB unreachable) fails.
check "api-health" "$API_HEALTH_URL" '"status":"UP"'
check "dashboard"  "$DASHBOARD_URL"
check "widget-cdn" "$WIDGET_URL"

if [[ "$ok" -ne 1 ]]; then
  echo "==> health check FAILED — withholding heartbeat so the monitor alerts" >&2
  exit 1
fi

if [[ -n "$HEARTBEAT_URL" ]]; then
  curl -sS -m "$TIMEOUT" -o /dev/null "$HEARTBEAT_URL" \
    || { echo "!! all healthy but heartbeat ping failed (${HEARTBEAT_URL})" >&2; exit 1; }
fi
echo "==> all healthy; heartbeat pinged"
