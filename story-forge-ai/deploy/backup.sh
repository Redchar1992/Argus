#!/usr/bin/env bash
set -euo pipefail

# MVP backup runbook. Run from deploy/ on the host with Docker Compose running.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="${BACKUP_DIR:-${ROOT_DIR}/backups/${STAMP}}"
mkdir -p "$BACKUP_DIR"

set -a
# shellcheck disable=SC1091
source "${ROOT_DIR}/.env"
set +a

docker compose --env-file "${ROOT_DIR}/.env" -f "${ROOT_DIR}/docker-compose.yml" exec -T mysql \
  mysqldump --single-transaction --routines --triggers \
  -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" \
  | gzip > "${BACKUP_DIR}/mysql.sql.gz"

docker run --rm \
  -v story-forge-chapter-checkpoints:/data:ro \
  -v "${BACKUP_DIR}:/backup" alpine:3.20 \
  sh -c 'tar czf /backup/chapter-checkpoints.tgz -C /data .'

docker run --rm \
  -v story-forge-exports:/data:ro \
  -v "${BACKUP_DIR}:/backup" alpine:3.20 \
  sh -c 'tar czf /backup/exports.tgz -C /data .'

printf 'Backups written to %s\n' "$BACKUP_DIR"
