#!/usr/bin/env bash
# =============================================================================
# backup.sh — pg_dump both environments, gzip, timestamp, rotate 30 days.
# Run from cron on the VPS (see server-setup.md), e.g. daily at 03:15.
#
# Off-site copy: after local dump, ship to Hetzner Object Storage with rclone:
#   rclone copy "$BACKUP_DIR" hetzner-s3:complyr-backups/ --min-age 0
# (configure the `hetzner-s3` remote once with `rclone config`; bucket in an
# EU region, server-side encryption on. Restore drill is part of the launch
# checklist — ARCHITECTURE.md §8.)
# =============================================================================
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/opt/complyr/backups}"
RETENTION_DAYS=30
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"

mkdir -p "$BACKUP_DIR"

backup_env() {
  local env_name="$1"
  local container="complyr-${env_name}-postgres-1"
  local outfile="${BACKUP_DIR}/complyr-${env_name}-${TIMESTAMP}.sql.gz"

  if ! docker ps --format '{{.Names}}' | grep -qx "$container"; then
    echo "!! ${container} not running — skipping ${env_name}" >&2
    return 0
  fi

  echo "==> Dumping ${env_name} (${container})"
  docker exec "$container" \
    pg_dump -U complyr --format=plain --no-owner complyr \
    | gzip > "$outfile"
  echo "==> Wrote ${outfile} ($(du -h "$outfile" | cut -f1))"
}

backup_env dev
backup_env prd

# Rotate: delete local dumps older than RETENTION_DAYS
find "$BACKUP_DIR" -name 'complyr-*.sql.gz' -mtime "+${RETENTION_DAYS}" -delete

# Off-site (uncomment once the rclone remote is configured):
# rclone copy "$BACKUP_DIR" hetzner-s3:complyr-backups/

echo "==> Backup run complete"
