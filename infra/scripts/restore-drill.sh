#!/usr/bin/env bash
# =============================================================================
# restore-drill.sh — prove a CookieKeeper backup is actually restorable.
#
# A backup you have never restored is a hope, not a backup. This script closes
# the launch-checklist "restore drill" item (ARCHITECTURE.md §8): it takes an
# encrypted dump (newest local by default, or one pulled from off-site),
# verifies its checksum, decrypts + decompresses it, restores it into a
# THROWAWAY scratch database, runs sanity queries, prints row counts, and drops
# the scratch DB. Production data is never touched.
#
# RUNS ON THE DATABASE HOST (cookiekeeper-{dev,prd}-db), alongside backup.sh and
# for the same reason: psql goes over the local unix socket under `peer` auth, so
# no password exists in this path and no plaintext crosses the network.
#
# The same decrypt+load pipeline is how a REAL recovery works — restore into a
# fresh DB, verify, then repoint the app (see server-setup.md §7). This script
# only differs by targeting a scratch DB and cleaning up after itself.
#
# Requires the age PRIVATE identity, which normally does NOT live on this host
# (backups are encrypted to a public key — see backup.sh). Supply it out-of-band
# for the drill: `--identity /path/to/age-identity.txt` or BACKUP_AGE_IDENTITY,
# and remove it afterwards.
#
# Usage:
#   restore-drill.sh [--env dev|prd] [--identity <file>]
#                    [--file <local.sql.gz.age> | --from-offsite <objectname>]
# =============================================================================
set -euo pipefail
umask 077

# --- defaults / config -------------------------------------------------------
# Which environment's dump to drill. Defaults to the one this machine holds,
# derived from the hostname Terraform gave it — the drill you actually want is
# the one against the data on the box you are standing on.
ENV_NAME="$(hostname -s | sed -n 's/^cookiekeeper-\(dev\|prd\)-db$/\1/p')"
BACKUP_DIR="${BACKUP_DIR:-/opt/cookiekeeper/backups}"
PG_OS_USER="${BACKUP_PG_OS_USER:-postgres}"      # peer auth: OS user == role
IDENTITY="${BACKUP_AGE_IDENTITY:-}"
RCLONE_REMOTE="${BACKUP_RCLONE_REMOTE:-}"
SOURCE_FILE=""                                   # explicit local file
OFFSITE_NAME=""                                  # explicit off-site object

# --- arg parsing -------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)          ENV_NAME="$2"; shift 2 ;;
    --identity)     IDENTITY="$2"; shift 2 ;;
    --file)         SOURCE_FILE="$2"; shift 2 ;;
    --from-offsite) OFFSITE_NAME="$2"; shift 2 ;;
    -h|--help)      grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "!! unknown arg: $1" >&2; exit 2 ;;
  esac
done

# --- preflight ---------------------------------------------------------------
[[ -n "$ENV_NAME" ]] \
  || { echo "!! cannot tell which environment this is: hostname '$(hostname -s)' is not cookiekeeper-{dev,prd}-db — pass --env" >&2; exit 1; }
command -v age >/dev/null 2>&1 || { echo "!! age not installed" >&2; exit 1; }
command -v runuser >/dev/null 2>&1 || { echo "!! runuser not found — cannot drop to ${PG_OS_USER}" >&2; exit 1; }
[[ -n "$IDENTITY" ]] || { echo "!! no age identity — pass --identity <file> or set BACKUP_AGE_IDENTITY" >&2; exit 1; }
[[ -f "$IDENTITY" ]] || { echo "!! identity file not found: $IDENTITY" >&2; exit 1; }
runuser -u "$PG_OS_USER" -- psql -qtAX -c 'SELECT 1' >/dev/null 2>&1 \
  || { echo "!! cannot reach postgres over the local socket as ${PG_OS_USER} — is postgresql running, and are you root?" >&2; exit 1; }

# Off-site objects are stored under a per-environment prefix (backup.sh) so the
# two database hosts cannot prune each other's copies.
REMOTE_PATH="${RCLONE_REMOTE%/}/${ENV_NAME}"

# Scratch space for a fetched off-site object; always cleaned up.
WORKDIR="$(mktemp -d)"
SCRATCH_DB="cookiekeeper_restore_drill_$(date -u +%Y%m%d%H%M%S)_$$"
cleanup() {
  local rc=$?
  # Drop the scratch DB. WITH (FORCE) evicts any lingering connection (a stuck
  # psql) so a restored PII database can't silently survive on this Postgres; a
  # failure here is WARNed loudly, not swallowed. Preserve the real exit code.
  if ! runuser -u "$PG_OS_USER" -- psql -d postgres -q \
        -c "DROP DATABASE IF EXISTS \"$SCRATCH_DB\" WITH (FORCE);" >/dev/null 2>&1; then
    echo "!! WARNING: could not drop scratch DB ${SCRATCH_DB} — drop it manually; it may hold restored PII" >&2
  fi
  rm -rf "$WORKDIR"
  exit "$rc"
}
trap cleanup EXIT

# --- resolve the source dump -------------------------------------------------
if [[ -n "$OFFSITE_NAME" ]]; then
  [[ -n "$RCLONE_REMOTE" ]] || { echo "!! --from-offsite needs BACKUP_RCLONE_REMOTE" >&2; exit 1; }
  command -v rclone >/dev/null 2>&1 || { echo "!! rclone not installed" >&2; exit 1; }
  echo "==> Fetching ${OFFSITE_NAME} from ${REMOTE_PATH}"
  SOURCE_FILE="${WORKDIR}/${OFFSITE_NAME}"
  rclone copyto "${REMOTE_PATH}/${OFFSITE_NAME}" "$SOURCE_FILE"
  # Pull the checksum sidecar too, if present, so we can verify.
  rclone copyto "${REMOTE_PATH}/${OFFSITE_NAME}.sha256" "${SOURCE_FILE}.sha256" 2>/dev/null || true
elif [[ -z "$SOURCE_FILE" ]]; then
  # Newest local dump for this env.
  SOURCE_FILE="$(find "$BACKUP_DIR" -maxdepth 1 -name "cookiekeeper-${ENV_NAME}-*.sql.gz.age" \
                  -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)"
  [[ -n "$SOURCE_FILE" ]] || { echo "!! no local dump found for env '${ENV_NAME}' in ${BACKUP_DIR}" >&2; exit 1; }
fi
[[ -f "$SOURCE_FILE" ]] || { echo "!! source dump not found: $SOURCE_FILE" >&2; exit 1; }
echo "==> Restore drill using: $(basename "$SOURCE_FILE")"

# --- verify checksum (if a sidecar exists) -----------------------------------
if [[ -f "${SOURCE_FILE}.sha256" ]]; then
  echo "==> Verifying SHA-256"
  ( cd "$(dirname "$SOURCE_FILE")" && sha256sum -c "$(basename "$SOURCE_FILE").sha256" ) \
    || { echo "!! checksum mismatch — backup is corrupt" >&2; exit 1; }
elif [[ -n "$OFFSITE_NAME" ]]; then
  # Transit corruption is most likely on the off-site path — refuse to certify
  # a backup "restorable" without an integrity check.
  echo "!! off-site object has no .sha256 sidecar — cannot verify transit integrity" >&2
  exit 1
else
  echo "!! no .sha256 sidecar — skipping checksum verification" >&2
fi

# --- create scratch DB, restore into it --------------------------------------
echo "==> Creating scratch DB ${SCRATCH_DB}"
runuser -u "$PG_OS_USER" -- psql -d postgres -q -c "CREATE DATABASE \"$SCRATCH_DB\";"

echo "==> Decrypting + restoring (ON_ERROR_STOP)"
# The end-to-end integrity proof: if the ciphertext, gzip, or SQL is bad, or the
# schema won't load, ON_ERROR_STOP=1 makes psql exit non-zero and the drill fails.
if ! age -d -i "$IDENTITY" "$SOURCE_FILE" \
      | gunzip \
      | runuser -u "$PG_OS_USER" -- psql -d "$SCRATCH_DB" -v ON_ERROR_STOP=1 -q; then
  echo "!! RESTORE FAILED — this backup is NOT restorable" >&2
  exit 1
fi

# --- sanity-verify the restored data -----------------------------------------
# Assert the core tables exist and are queryable; consent_events is the audit
# evidence that MUST survive (constraint #3), so we count it explicitly.
echo "==> Verifying restored schema + row counts"
runuser -u "$PG_OS_USER" -- psql -d "$SCRATCH_DB" -v ON_ERROR_STOP=1 -At <<'SQL' || { echo "!! sanity queries FAILED" >&2; exit 1; }
SELECT 'users='        || count(*) FROM users;
SELECT 'sites='        || count(*) FROM sites;
SELECT 'consent_events=' || count(*) FROM consent_events;
SQL

echo "==> RESTORE DRILL PASSED — backup is restorable (scratch DB dropped on exit)"
