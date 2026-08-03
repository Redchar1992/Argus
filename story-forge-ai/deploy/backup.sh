#!/usr/bin/env bash
set -Eeuo pipefail

# Creates one quiesced, checksummed backup set for the state that cannot be
# reconstructed from the application images. The application services are
# stopped while MySQL, Redis durable state, both SQLite checkpointers, and
# exports are captured.

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
COMPOSE_OVERRIDE_FILE="${COMPOSE_OVERRIDE_FILE:-}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_ROOT="${BACKUP_DIR:-${ROOT_DIR}/backups}"
STOP_TIMEOUT_SECONDS="${BACKUP_STOP_TIMEOUT_SECONDS:-60}"
REDIS_HEALTH_TIMEOUT_SECONDS="${REDIS_HEALTH_TIMEOUT_SECONDS:-30}"
BACKUP_FORMAT="story-forge-backup-v2"

die() {
  printf 'backup: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die 'sha256sum or shasum is required'
  fi
}

[[ -f "$ENV_FILE" ]] || die "environment file not found: $ENV_FILE"
[[ -f "$COMPOSE_FILE" ]] || die "compose file not found: $COMPOSE_FILE"
[[ -z "$COMPOSE_OVERRIDE_FILE" || -f "$COMPOSE_OVERRIDE_FILE" ]] || die "compose override file not found: $COMPOSE_OVERRIDE_FILE"
[[ "$STOP_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die 'BACKUP_STOP_TIMEOUT_SECONDS must be a positive integer'
[[ "$REDIS_HEALTH_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die 'REDIS_HEALTH_TIMEOUT_SECONDS must be a positive integer'

require_command docker
require_command gzip
require_command awk

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
if [[ -n "$COMPOSE_OVERRIDE_FILE" ]]; then
  COMPOSE+=(-f "$COMPOSE_OVERRIDE_FILE")
fi
"${COMPOSE[@]}" config --quiet

mkdir -p "$BACKUP_ROOT"
BACKUP_ROOT="$(cd "$BACKUP_ROOT" && pwd -P)"
FINAL_DIR="${BACKUP_ROOT}/${STAMP}"
[[ ! -e "$FINAL_DIR" ]] || die "backup destination already exists: $FINAL_DIR"
STAGING_DIR="$(mktemp -d "${BACKUP_ROOT}/.${STAMP}.XXXXXX")"

APP_SERVICES=(frontend backend ai-worker chapter-worker ai-service)
DEFINED_SERVICES="$("${COMPOSE[@]}" config --services)"
while IFS= read -r service; do
  if [[ "$service" == 'caddy' ]]; then
    APP_SERVICES=(caddy "${APP_SERVICES[@]}")
    break
  fi
done <<<"$DEFINED_SERVICES"
STOPPED_SERVICES=()
RUNNING_SERVICES="$("${COMPOSE[@]}" ps --status running --services)"
BACKUP_COMPLETE=0
REDIS_RESTART_REQUIRED=0

was_running() {
  local expected="$1"
  local service
  while IFS= read -r service; do
    [[ "$service" == "$expected" ]] && return 0
  done <<<"$RUNNING_SERVICES"
  return 1
}

resume_services() {
  local service
  local candidate
  local should_start
  local failed=0

  if ((REDIS_RESTART_REQUIRED)); then
    if ! "${COMPOSE[@]}" start redis; then
      return 1
    fi
    if ! wait_for_redis; then
      printf 'backup: Redis did not become healthy after restart\n' >&2
      return 1
    fi
    REDIS_RESTART_REQUIRED=0
  fi

  ((${#STOPPED_SERVICES[@]} > 0)) || return 0

  # Start producers last. These services were running before the backup, so
  # start does not create or enable a previously disabled service.
  for candidate in ai-service ai-worker chapter-worker backend frontend caddy; do
    should_start=0
    for service in "${STOPPED_SERVICES[@]}"; do
      [[ "$service" == "$candidate" ]] && should_start=1
    done
    if ((should_start)); then
      if ! "${COMPOSE[@]}" start "$candidate"; then
        failed=1
      fi
    fi
  done
  return "$failed"
}

wait_for_redis() {
  local attempt=0
  local response

  while ((attempt < REDIS_HEALTH_TIMEOUT_SECONDS)); do
    response="$("${COMPOSE[@]}" exec -T redis sh -ceu '
      exec redis-cli --no-auth-warning -a "$REDIS_PASSWORD" ping
    ' 2>/dev/null || true)"
    if [[ "$response" == 'PONG' ]]; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 1
  done
  return 1
}

cleanup() {
  local rc=$?
  trap - EXIT INT TERM

  if ! resume_services; then
    printf 'backup: WARNING: Redis or one or more application services could not be restarted\n' >&2
    rc=1
  fi

  if ((BACKUP_COMPLETE == 0)) && [[ -n "${STAGING_DIR:-}" && -d "$STAGING_DIR" ]]; then
    rm -rf -- "$STAGING_DIR"
  fi

  exit "$rc"
}
trap cleanup EXIT INT TERM

resolve_compose_volume() {
  local logical_name="$1"
  local resolved

  resolved="$("${COMPOSE[@]}" config | awk -v logical="$logical_name" '
    $0 == "volumes:" { in_volumes = 1; next }
    in_volumes && $0 == "  " logical ":" { wanted = 1; next }
    wanted && $1 == "name:" { print $2; found = 1; wanted = 0 }
    END { if (!found) exit 1 }
  ')" || die "could not resolve Compose volume: $logical_name"

  [[ "$resolved" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]*$ ]] || die "unsafe Compose volume name: $resolved"
  printf '%s\n' "$resolved"
}

backup_sqlite() {
  local service="$1"
  local source_path="$2"
  local output_name="$3"

  "${COMPOSE[@]}" run --rm --no-deps -T \
    --user 0:0 \
    -v "${STAGING_DIR}:/backup" \
    --entrypoint python "$service" -c '
import os
import pathlib
import sqlite3
import sys

source_path = pathlib.Path(sys.argv[1])
target_path = pathlib.Path(sys.argv[2])
if not source_path.is_file():
    raise SystemExit(f"checkpoint database is missing: {source_path}")

source = sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True)
target = sqlite3.connect(target_path)
try:
    target.execute("PRAGMA journal_mode=DELETE")
    source.backup(target)
    target.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    target.execute("PRAGMA journal_mode=DELETE")
    result = target.execute("PRAGMA integrity_check").fetchone()
    if not result or result[0] != "ok":
        raise SystemExit(f"checkpoint integrity check failed: {result}")
finally:
    target.close()
    source.close()
os.chown(target_path, int(sys.argv[3]), int(sys.argv[4]))
os.chmod(target_path, 0o600)
' "$source_path" "/backup/${output_name}" "$(id -u)" "$(id -g)"
}

backup_volume_archive() {
  local volume_name="$1"
  local output_name="$2"
  local label="$3"

  docker volume inspect "$volume_name" >/dev/null
  "${COMPOSE[@]}" run --rm --no-deps -T \
    --user 0:0 \
    -v "${volume_name}:/source:ro" \
    -v "${STAGING_DIR}:/backup" \
    --entrypoint python ai-worker -c '
import os
import pathlib
import sys
import tarfile

source_root = pathlib.Path("/source")
output_path = pathlib.Path("/backup") / sys.argv[1]
label = sys.argv[2]

if label == "Redis":
    required = (
        source_root / "dump.rdb",
        source_root / "appendonlydir" / "appendonly.aof.manifest",
    )
    missing = [str(path) for path in required if not path.is_file() or path.stat().st_size == 0]
    if missing:
        raise SystemExit(f"Redis persistence files are missing or empty: {missing}")

with tarfile.open(output_path, "w:gz", format=tarfile.PAX_FORMAT) as archive:
    for path in sorted(source_root.rglob("*"), key=lambda item: item.as_posix()):
        if path.is_symlink():
            raise SystemExit(f"refusing to archive {label} symlink: {path}")
        if not path.is_dir() and not path.is_file():
            raise SystemExit(f"refusing to archive special {label} file: {path}")
        archive.add(path, arcname=path.relative_to(source_root).as_posix(), recursive=False)
os.chown(output_path, int(sys.argv[3]), int(sys.argv[4]))
os.chmod(output_path, 0o600)
' "$output_name" "$label" "$(id -u)" "$(id -g)"
}

MYSQL_RUNNING="$("${COMPOSE[@]}" ps --status running --services mysql)"
[[ "$MYSQL_RUNNING" == 'mysql' ]] || die 'the mysql service must be running'
REDIS_RUNNING="$("${COMPOSE[@]}" ps --status running --services redis)"
[[ "$REDIS_RUNNING" == 'redis' ]] || die 'the redis service must be running'

MYSQL_DATABASE_NAME="$("${COMPOSE[@]}" exec -T mysql sh -c 'printf "%s" "$MYSQL_DATABASE"')"
[[ "$MYSQL_DATABASE_NAME" =~ ^[A-Za-z0-9_]+$ ]] || die 'the configured MySQL database name is unsafe'

EXPORT_VOLUME="$(resolve_compose_volume story-forge-exports)"
REDIS_VOLUME="$(resolve_compose_volume story-forge-redis)"

for service in "${APP_SERVICES[@]}"; do
  if was_running "$service"; then
    STOPPED_SERVICES+=("$service")
  fi
done

if ((${#STOPPED_SERVICES[@]} > 0)); then
  printf 'Quiescing application writes...\n'
  "${COMPOSE[@]}" stop -t "$STOP_TIMEOUT_SECONDS" "${STOPPED_SERVICES[@]}"
fi

printf 'Flushing and stopping Redis...\n'
REDIS_SAVE_RESULT="$("${COMPOSE[@]}" exec -T redis sh -ceu '
  exec redis-cli --no-auth-warning -a "$REDIS_PASSWORD" SAVE
')"
[[ "$REDIS_SAVE_RESULT" == 'OK' ]] || die "Redis SAVE failed: ${REDIS_SAVE_RESULT}"
REDIS_RESTART_REQUIRED=1
"${COMPOSE[@]}" stop -t "$STOP_TIMEOUT_SECONDS" redis

printf 'Backing up MySQL...\n'
"${COMPOSE[@]}" exec -T mysql sh -ceu '
  exec env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump \
    --user=root \
    --single-transaction \
    --routines \
    --triggers \
    --events \
    --hex-blob \
    --set-gtid-purged=OFF \
    --add-drop-database \
    --databases "$MYSQL_DATABASE"
' | gzip -9 >"${STAGING_DIR}/mysql.sql.gz"
gzip -t "${STAGING_DIR}/mysql.sql.gz"

printf 'Backing up story checkpoints...\n'
backup_sqlite ai-worker /data/story/story-checkpoints.sqlite story-checkpoints.sqlite

printf 'Backing up chapter checkpoints...\n'
backup_sqlite chapter-worker /data/chapter-checkpoints.sqlite chapter-checkpoints.sqlite

printf 'Backing up exports...\n'
backup_volume_archive "$EXPORT_VOLUME" exports.tar.gz exports
gzip -t "${STAGING_DIR}/exports.tar.gz"

printf 'Backing up Redis durable state...\n'
backup_volume_archive "$REDIS_VOLUME" redis-data.tar.gz Redis
gzip -t "${STAGING_DIR}/redis-data.tar.gz"

cat >"${STAGING_DIR}/manifest.txt" <<EOF
format=${BACKUP_FORMAT}
created_at=${STAMP}
mysql_database=${MYSQL_DATABASE_NAME}
EOF

(
  cd "$STAGING_DIR"
  for file in manifest.txt mysql.sql.gz story-checkpoints.sqlite chapter-checkpoints.sqlite exports.tar.gz redis-data.tar.gz; do
    printf '%s  %s\n' "$(sha256_file "$file")" "$file"
  done >SHA256SUMS
)

mv "$STAGING_DIR" "$FINAL_DIR"
STAGING_DIR=''
BACKUP_COMPLETE=1

printf 'Verified backup written to %s\n' "$FINAL_DIR"
printf 'Run DRY_RUN=1 %s/restore.sh %q before a real restore.\n' "$ROOT_DIR" "$FINAL_DIR"
