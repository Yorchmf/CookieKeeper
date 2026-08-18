#!/usr/bin/env bash
# =============================================================================
# backup.sh — encrypted, verified, off-site Postgres backups for CookieKeeper.
#
# For each environment: pg_dump -> gzip -> age-encrypt in ONE pipeline (plaintext
# never touches disk), write a SHA-256 sidecar, ship to Hetzner Object Storage
# (EU region) with rclone, then rotate local + off-site copies.
#
# Runs from cron on the VPS (see server-setup.md §7), e.g. daily at 03:15.
#
# WHY client-side encryption with a *recipient* (public) key:
#   Dumps contain visitor PII and append-only consent audit evidence (CLAUDE.md
#   constraints #3, #4). age encrypts to a PUBLIC key, so this host can WRITE
#   backups but can NEVER decrypt them — a compromised VPS cannot read its own
#   backup history. The matching private identity lives OFFLINE (password
#   manager) and is only supplied during a restore drill (restore-drill.sh).
#   The bucket must be EU-region (constraint #2); Hetzner Object Storage is the
#   same EU provider as the VPS, so it introduces no new data processor.
#
# Config is via env (see .env.example "Backups"); the cron line sources
# /opt/cookiekeeper/backup.env so secrets/keys stay out of the crontab and out of git.
# =============================================================================
set -euo pipefail
umask 077   # dumps are PII — never group/world-readable

# --- config (env-overridable) ------------------------------------------------
BACKUP_DIR="${BACKUP_DIR:-/opt/cookiekeeper/backups}"
BACKUP_ENVS="${BACKUP_ENVS:-dev prd}"
LOCAL_RETENTION_DAYS="${BACKUP_LOCAL_RETENTION_DAYS:-14}"   # fast local restores
OFFSITE_RETENTION_DAYS="${BACKUP_OFFSITE_RETENTION_DAYS:-90}"  # disaster window
PG_USER="${BACKUP_PG_USER:-cookiekeeper}}"
PG_DB="${BACKUP_PG_DB:-cookiekeeper}}"
AGE_RECIPIENT="${BACKUP_AGE_RECIPIENT:-}"    # age1... public key(s), space-separated
RCLONE_REMOTE="${BACKUP_RCLONE_REMOTE:-}"    # e.g. hetzner-s3:cookiekeeper-backups (off-site; optional)

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"       # UTC, sortable, filename-safe

# --- preflight: fail loudly rather than silently degrade security ------------
command -v age >/dev/null 2>&1 \
  || { echo "!! age not installed (apt-get install -y age) — refusing to run" >&2; exit 1; }
if [[ -z "$AGE_RECIPIENT" ]]; then
  echo "!! BACKUP_AGE_RECIPIENT unset — refusing to write an UNENCRYPTED backup" >&2
  exit 1
fi

# age accepts multiple recipients (e.g. a break-glass second key); expand each
# into its own -r flag so recovery is possible with any one matching identity.
read -r -a _recipients <<< "$AGE_RECIPIENT"
age_args=()
for r in "${_recipients[@]}"; do age_args+=(-r "$r"); done

mkdir -p "$BACKUP_DIR"

# Fail hard if the Docker daemon is unreachable. Otherwise every container name
# is "not running", each env is silently skipped, and we'd report success while
# backing up NOTHING (a broken docker group, dead daemon, etc.).
docker ps >/dev/null 2>&1 \
  || { echo "!! docker daemon unreachable — cannot back up anything" >&2; exit 1; }

fail=0   # any per-env/off-site failure flips this; the run still exits non-zero
         # so the cron log + uptime monitor surface a broken backup.
made=0   # count of dumps actually written; zero at the end means total failure.

# EU residency (constraint #2): refuse to ship PII off-site to a positively
# non-EU endpoint. Best-effort — reads the ACTUAL rclone remote config; unknown
# region → warn-and-proceed (Hetzner Object Storage is EU-only), clearly non-EU
# host → hard refuse. Returns 0 only when the endpoint is positively non-EU.
offsite_is_non_eu() {
  local remote_name="${RCLONE_REMOTE%%:*}" endpoint
  endpoint="$(rclone config show "$remote_name" 2>/dev/null \
                | sed -n 's/^[[:space:]]*endpoint[[:space:]]*=[[:space:]]*//p' \
                | tr 'A-Z' 'a-z')"
  [[ -n "$endpoint" ]] || return 1                       # unknown → not positively non-EU
  case "$endpoint" in
    *fsn1*|*nbg1*|*hel1*|*eu-*|*.eu.*|*europe*) return 1 ;;  # known EU region
    *) return 0 ;;                                            # positively non-EU
  esac
}

# Ship a file (and its sidecar) off-site, then trust rclone's post-transfer
# checksum verification (enabled by default for S3). Absence of a remote is a
# supported "local-only" mode, but a configured-yet-failing remote is an error.
upload_offsite() {
  [[ -n "$RCLONE_REMOTE" ]] || { echo "==> off-site disabled (BACKUP_RCLONE_REMOTE unset) — local-only"; return 0; }
  if ! command -v rclone >/dev/null 2>&1; then
    echo "!! rclone not installed but BACKUP_RCLONE_REMOTE is set — cannot ship off-site" >&2
    fail=1
    return 0
  fi
  if offsite_is_non_eu; then
    echo "!! off-site endpoint for ${RCLONE_REMOTE} is not an EU region — refusing to upload PII (constraint #2); local copy kept" >&2
    fail=1
    return 0
  fi
  local f
  for f in "$@"; do
    echo "==> Uploading $(basename "$f") -> ${RCLONE_REMOTE}"
    if ! rclone copyto "$f" "${RCLONE_REMOTE}/$(basename "$f")"; then
      echo "!! off-site upload FAILED for $(basename "$f")" >&2
      fail=1
    fi
  done
}

backup_env() {
  local env_name="$1"
  local container="cookiekeeper-${env_name}-postgres-1"
  local base="${BACKUP_DIR}/cookiekeeper-${env_name}-${TIMESTAMP}.sql.gz.age"
  local tmp="${base}.partial"

  if ! docker ps --format '{{.Names}}' | grep -qx "$container"; then
    echo "!! ${container} not running — skipping ${env_name}" >&2
    return 0
  fi

  echo "==> Dumping ${env_name} (${container}) -> encrypted"
  # Straight pipeline: plaintext exists only in-flight between processes, never
  # on disk. pipefail makes ANY stage's failure (pg_dump, gzip, age) fail the
  # whole command; we then drop the partial file and flag the run.
  if ! docker exec "$container" \
        pg_dump -U "$PG_USER" --format=plain --no-owner "$PG_DB" \
        | gzip \
        | age "${age_args[@]}" > "$tmp"; then
    echo "!! dump/encrypt FAILED for ${env_name}" >&2
    rm -f "$tmp"
    fail=1
    return 0
  fi
  # Finalize: atomic rename (readers never see a partial), then a checksum
  # sidecar recorded with a RELATIVE name (cd into the dir first) so `sha256sum
  # -c` verifies correctly wherever the pair is later restored from — including
  # a fresh disaster-recovery host or a temp dir after an off-site fetch. A
  # finalize failure (e.g. disk full) flags the run but still lets other envs run.
  if ! { mv "$tmp" "$base" \
          && ( cd "$BACKUP_DIR" && sha256sum "$(basename "$base")" > "$(basename "$base").sha256" ); }; then
    echo "!! failed to finalize ${env_name} backup (mv/checksum)" >&2
    rm -f "$tmp" "$base" "${base}.sha256"
    fail=1
    return 0
  fi
  made=$((made + 1))
  echo "==> Wrote $(basename "$base") ($(du -h "$base" | cut -f1))"

  upload_offsite "$base" "${base}.sha256"
}

for env_name in $BACKUP_ENVS; do
  backup_env "$env_name"
done

# --- rotation ----------------------------------------------------------------
# Local: keep a short window for fast restores.
find "$BACKUP_DIR" -maxdepth 1 -name 'cookiekeeper-*.sql.gz.age'        -mtime "+${LOCAL_RETENTION_DAYS}" -delete
find "$BACKUP_DIR" -maxdepth 1 -name 'cookiekeeper-*.sql.gz.age.sha256' -mtime "+${LOCAL_RETENTION_DAYS}" -delete

# Off-site: keep a longer disaster-recovery window. (Belt-and-suspenders — set a
# bucket lifecycle rule too, in case this host is the thing that failed.)
if [[ -n "$RCLONE_REMOTE" ]] && command -v rclone >/dev/null 2>&1; then
  echo "==> Pruning off-site copies older than ${OFFSITE_RETENTION_DAYS}d"
  rclone delete "$RCLONE_REMOTE" --min-age "${OFFSITE_RETENTION_DAYS}d" \
    || { echo "!! off-site prune FAILED" >&2; fail=1; }
fi

if [[ "$made" -eq 0 ]]; then
  echo "!! no backups were produced — every env in '${BACKUP_ENVS}' was skipped; check container names" >&2
  fail=1
fi

if [[ "$fail" -ne 0 ]]; then
  echo "==> Backup run completed WITH ERRORS" >&2
  exit 1
fi
echo "==> Backup run complete (${made} dump(s) written)"
