#!/usr/bin/env bash
# =============================================================================
# backup.sh — encrypted, verified, off-site Postgres backups for CookieKeeper.
#
# pg_dump -> gzip -> age-encrypt in ONE pipeline (plaintext never touches disk),
# write a SHA-256 sidecar, ship to Hetzner Object Storage (EU region) with
# rclone, then rotate local + off-site copies.
#
# RUNS ON THE DATABASE HOST (cookiekeeper-{dev,prd}-db), from cron — see
# server-setup.md §7, e.g. daily at 03:15. Not on the app host, and one
# environment per machine since ADR-24. That placement is deliberate:
#   * pg_dump goes over the local unix socket, so the plaintext dump never
#     crosses the network at all — not even the private one;
#   * the app host never needs a credential that can read every table, which is
#     the credential you least want on the box that runs a web crawler.
#
# WHY client-side encryption with a *recipient* (public) key:
#   Dumps contain visitor PII and append-only consent audit evidence (CLAUDE.md
#   constraints #3, #4). age encrypts to a PUBLIC key, so this host can WRITE
#   backups but can NEVER decrypt them — a compromised host cannot read its own
#   backup history. The matching private identity lives OFFLINE (password
#   manager) and is only supplied during a restore drill (restore-drill.sh).
#   The bucket must be EU-region (constraint #2); Hetzner Object Storage is the
#   same EU provider as the servers, so it introduces no new data processor.
#
# Config is via env (see .env.example "Backups"); the cron line sources
# /opt/cookiekeeper/backup.env so secrets/keys stay out of the crontab and out of git.
# =============================================================================
set -euo pipefail
umask 077   # dumps are PII — never group/world-readable

# --- config (env-overridable) ------------------------------------------------
BACKUP_DIR="${BACKUP_DIR:-/opt/cookiekeeper/backups}"
LOCAL_RETENTION_DAYS="${BACKUP_LOCAL_RETENTION_DAYS:-14}"   # fast local restores
OFFSITE_RETENTION_DAYS="${BACKUP_OFFSITE_RETENTION_DAYS:-90}"  # disaster window
# The dump runs as the `postgres` OS user over the unix socket, where pg_hba is
# `peer` — so the OS user and the database role must be the same name, and only
# `postgres` satisfies that. There is no password anywhere in this path.
PG_OS_USER="${BACKUP_PG_OS_USER:-postgres}"
PG_DB="${BACKUP_PG_DB:-cookiekeeper}"
AGE_RECIPIENT="${BACKUP_AGE_RECIPIENT:-}"    # age1... public key(s), space-separated
RCLONE_REMOTE="${BACKUP_RCLONE_REMOTE:-}"    # e.g. hetzner-s3:cookiekeeper-backups (off-site; optional)

# Which environment's data this is, for the filename. Derived from the hostname
# Terraform gave the machine so a correctly-built host needs no extra config;
# override in backup.env if you ever rename one. Guessing wrong would file prd's
# dump under dev's name, so an underivable name is fatal rather than defaulted.
BACKUP_ENV="${BACKUP_ENV:-$(hostname -s | sed -n 's/^cookiekeeper-\(dev\|prd\)-db$/\1/p')}"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"       # UTC, sortable, filename-safe

# Everything off-site goes under a per-environment prefix, appended HERE rather
# than left to whoever writes backup.env. Two database hosts now back up
# independently, and the rotation below is a `delete --min-age` against a path:
# an unscoped remote would have dev's nightly cron pruning prd's only copy of
# production. The prefix makes that impossible by construction.
REMOTE_PATH="${RCLONE_REMOTE%/}/${BACKUP_ENV}"

# --- preflight: fail loudly rather than silently degrade security ------------
[[ -n "$BACKUP_ENV" ]] \
  || { echo "!! cannot tell which environment this is: hostname '$(hostname -s)' is not cookiekeeper-{dev,prd}-db and BACKUP_ENV is unset" >&2; exit 1; }
command -v age >/dev/null 2>&1 \
  || { echo "!! age not installed (apt-get install -y age) — refusing to run" >&2; exit 1; }
command -v runuser >/dev/null 2>&1 \
  || { echo "!! runuser not found — cannot drop to ${PG_OS_USER} for the dump" >&2; exit 1; }
if [[ -z "$AGE_RECIPIENT" ]]; then
  echo "!! BACKUP_AGE_RECIPIENT unset — refusing to write an UNENCRYPTED backup" >&2
  exit 1
fi

# Fail hard if the database is not answering. Without this the dump failure would
# still be caught below, but this says WHY in one line instead of leaving a
# pg_dump error to be read out of a cron mail.
runuser -u "$PG_OS_USER" -- psql -qtAX -c 'SELECT 1' >/dev/null 2>&1 \
  || { echo "!! cannot reach postgres over the local socket as ${PG_OS_USER} — is postgresql running?" >&2; exit 1; }

# age accepts multiple recipients (e.g. a break-glass second key); expand each
# into its own -r flag so recovery is possible with any one matching identity.
read -r -a _recipients <<< "$AGE_RECIPIENT"
age_args=()
for r in "${_recipients[@]}"; do age_args+=(-r "$r"); done

mkdir -p "$BACKUP_DIR"

fail=0   # any failure flips this; the run still exits non-zero so the cron log +
         # uptime monitor surface a broken backup.

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
    echo "==> Uploading $(basename "$f") -> ${REMOTE_PATH}"
    if ! rclone copyto "$f" "${REMOTE_PATH}/$(basename "$f")"; then
      echo "!! off-site upload FAILED for $(basename "$f")" >&2
      fail=1
    fi
  done
}

base="${BACKUP_DIR}/cookiekeeper-${BACKUP_ENV}-${TIMESTAMP}.sql.gz.age"
tmp="${base}.partial"

echo "==> Dumping ${BACKUP_ENV} (${PG_DB}, local socket) -> encrypted"
# Straight pipeline: plaintext exists only in-flight between processes, never on
# disk. pipefail makes ANY stage's failure (pg_dump, gzip, age) fail the whole
# command; we then drop the partial file and flag the run.
if ! runuser -u "$PG_OS_USER" -- pg_dump --format=plain --no-owner "$PG_DB" \
      | gzip \
      | age "${age_args[@]}" > "$tmp"; then
  echo "!! dump/encrypt FAILED for ${BACKUP_ENV}" >&2
  rm -f "$tmp"
  fail=1
fi

if [[ "$fail" -eq 0 ]]; then
  # Finalize: atomic rename (readers never see a partial), then a checksum
  # sidecar recorded with a RELATIVE name (cd into the dir first) so `sha256sum
  # -c` verifies correctly wherever the pair is later restored from — including
  # a fresh disaster-recovery host or a temp dir after an off-site fetch.
  if ! { mv "$tmp" "$base" \
          && ( cd "$BACKUP_DIR" && sha256sum "$(basename "$base")" > "$(basename "$base").sha256" ); }; then
    echo "!! failed to finalize the ${BACKUP_ENV} backup (mv/checksum)" >&2
    rm -f "$tmp" "$base" "${base}.sha256"
    fail=1
  else
    echo "==> Wrote $(basename "$base") ($(du -h "$base" | cut -f1))"
    upload_offsite "$base" "${base}.sha256"
  fi
fi

# --- rotation ----------------------------------------------------------------
# Local: keep a short window for fast restores.
find "$BACKUP_DIR" -maxdepth 1 -name 'cookiekeeper-*.sql.gz.age'        -mtime "+${LOCAL_RETENTION_DAYS}" -delete
find "$BACKUP_DIR" -maxdepth 1 -name 'cookiekeeper-*.sql.gz.age.sha256' -mtime "+${LOCAL_RETENTION_DAYS}" -delete

# Off-site: keep a longer disaster-recovery window. (Belt-and-suspenders — set a
# bucket lifecycle rule too, in case this host is the thing that failed.)
if [[ -n "$RCLONE_REMOTE" ]] && command -v rclone >/dev/null 2>&1; then
  echo "==> Pruning off-site ${BACKUP_ENV} copies older than ${OFFSITE_RETENTION_DAYS}d"
  rclone delete "$REMOTE_PATH" --min-age "${OFFSITE_RETENTION_DAYS}d" \
    || { echo "!! off-site prune FAILED" >&2; fail=1; }
fi

if [[ "$fail" -ne 0 ]]; then
  echo "==> Backup run completed WITH ERRORS" >&2
  exit 1
fi
echo "==> Backup run complete"
