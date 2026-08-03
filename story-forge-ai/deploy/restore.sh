#!/usr/bin/env bash
set -Eeuo pipefail

# Restores a backup produced by backup.sh. Validation always happens before
# target services are stopped. DRY_RUN=1 performs validation only; a real
# restore requires an interactive confirmation or FORCE=1.

umask 077

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-${ROOT_DIR}/docker-compose.yml}"
COMPOSE_OVERRIDE_FILE="${COMPOSE_OVERRIDE_FILE:-}"
STOP_TIMEOUT_SECONDS="${BACKUP_STOP_TIMEOUT_SECONDS:-60}"
MAX_EXPORT_BYTES="${RESTORE_MAX_EXPORT_BYTES:-21474836480}"
MAX_REDIS_BYTES="${RESTORE_MAX_REDIS_BYTES:-4294967296}"
REDIS_HEALTH_TIMEOUT_SECONDS="${REDIS_HEALTH_TIMEOUT_SECONDS:-30}"
EXPECTED_FORMAT="story-forge-backup-v2"
DRY_RUN_VALUE="${DRY_RUN:-0}"
FORCE_VALUE="${FORCE:-0}"

usage() {
  cat <<'EOF'
Usage: restore.sh [--dry-run] [--force] BACKUP_DIRECTORY

  --dry-run  Verify the manifest, checksums, SQLite files, and exports archive
             without changing application data.
  --force    Execute a restore without the interactive confirmation prompt.

Environment overrides: ENV_FILE, COMPOSE_FILE, COMPOSE_OVERRIDE_FILE,
BACKUP_STOP_TIMEOUT_SECONDS, RESTORE_MAX_EXPORT_BYTES,
RESTORE_MAX_REDIS_BYTES, REDIS_HEALTH_TIMEOUT_SECONDS, DRY_RUN=1, FORCE=1.
EOF
}

die() {
  printf 'restore: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

is_true() {
  case "${1:-}" in
    1 | true | TRUE | yes | YES) return 0 ;;
    0 | false | FALSE | no | NO | '') return 1 ;;
    *) die "boolean flag must be one of 0/1/true/false/yes/no: $1" ;;
  esac
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

read_manifest_value() {
  local key="$1"
  local value

  value="$(awk -F= -v key="$key" '
    $1 == key { count += 1; sub(/^[^=]*=/, ""); value = $0 }
    END { if (count == 1) print value; else exit 1 }
  ' "${BACKUP_PATH}/manifest.txt")" || die "manifest must contain exactly one ${key} entry"
  printf '%s\n' "$value"
}

verify_checksum() {
  local filename="$1"
  local expected
  local actual

  expected="$(awk -v filename="$filename" '
    $2 == filename { count += 1; value = $1 }
    END { if (count == 1) print value; else exit 1 }
  ' "${BACKUP_PATH}/SHA256SUMS")" || die "checksum list must contain exactly one ${filename} entry"
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || die "invalid checksum for ${filename}"
  expected="$(printf '%s' "$expected" | tr 'A-F' 'a-f')"
  actual="$(sha256_file "${BACKUP_PATH}/${filename}")"
  [[ "$actual" == "$expected" ]] || die "checksum mismatch: ${filename}"
}

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

POSITIONAL=()
while (($# > 0)); do
  case "$1" in
    --dry-run) DRY_RUN_VALUE=1 ;;
    --force) FORCE_VALUE=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    --) shift; POSITIONAL+=("$@"); break ;;
    -*) die "unknown option: $1" ;;
    *) POSITIONAL+=("$1") ;;
  esac
  shift
done

((${#POSITIONAL[@]} == 1)) || {
  usage >&2
  exit 2
}

INPUT_PATH="${POSITIONAL[0]}"
[[ -d "$INPUT_PATH" ]] || die "backup directory not found: $INPUT_PATH"
[[ ! -L "$INPUT_PATH" ]] || die 'the backup directory must not be a symlink'
BACKUP_PATH="$(cd "$INPUT_PATH" && pwd -P)"
[[ "$BACKUP_PATH" != '/' ]] || die 'refusing to use the filesystem root as a backup directory'

REQUIRED_FILES=(manifest.txt SHA256SUMS mysql.sql.gz story-checkpoints.sqlite chapter-checkpoints.sqlite exports.tar.gz redis-data.tar.gz)
for file in "${REQUIRED_FILES[@]}"; do
  [[ -f "${BACKUP_PATH}/${file}" ]] || die "required backup file is missing: ${file}"
  [[ ! -L "${BACKUP_PATH}/${file}" ]] || die "backup files must not be symlinks: ${file}"
done

require_command docker
require_command gzip
require_command awk

[[ -f "$ENV_FILE" ]] || die "environment file not found: $ENV_FILE"
[[ -f "$COMPOSE_FILE" ]] || die "compose file not found: $COMPOSE_FILE"
[[ -z "$COMPOSE_OVERRIDE_FILE" || -f "$COMPOSE_OVERRIDE_FILE" ]] || die "compose override file not found: $COMPOSE_OVERRIDE_FILE"
[[ "$STOP_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die 'BACKUP_STOP_TIMEOUT_SECONDS must be a positive integer'
[[ "$MAX_EXPORT_BYTES" =~ ^[1-9][0-9]*$ ]] || die 'RESTORE_MAX_EXPORT_BYTES must be a positive integer'
[[ "$MAX_REDIS_BYTES" =~ ^[1-9][0-9]*$ ]] || die 'RESTORE_MAX_REDIS_BYTES must be a positive integer'
[[ "$REDIS_HEALTH_TIMEOUT_SECONDS" =~ ^[1-9][0-9]*$ ]] || die 'REDIS_HEALTH_TIMEOUT_SECONDS must be a positive integer'

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
if [[ -n "$COMPOSE_OVERRIDE_FILE" ]]; then
  COMPOSE+=(-f "$COMPOSE_OVERRIDE_FILE")
fi
"${COMPOSE[@]}" config --quiet

CHECKSUM_ENTRIES="$(awk '
  NF != 2 { exit 1 }
  $2 != "manifest.txt" &&
  $2 != "mysql.sql.gz" &&
  $2 != "story-checkpoints.sqlite" &&
  $2 != "chapter-checkpoints.sqlite" &&
  $2 != "exports.tar.gz" &&
  $2 != "redis-data.tar.gz" { exit 1 }
  { count += 1 }
  END { if (count != 6) exit 1; print count }
' "${BACKUP_PATH}/SHA256SUMS")" || die 'checksum list contains missing, duplicate, malformed, or unexpected paths'
[[ "$CHECKSUM_ENTRIES" == '6' ]] || die 'invalid checksum list'

for file in manifest.txt mysql.sql.gz story-checkpoints.sqlite chapter-checkpoints.sqlite exports.tar.gz redis-data.tar.gz; do
  verify_checksum "$file"
done
gzip -t "${BACKUP_PATH}/mysql.sql.gz"
gzip -t "${BACKUP_PATH}/exports.tar.gz"
gzip -t "${BACKUP_PATH}/redis-data.tar.gz"

BACKUP_FORMAT="$(read_manifest_value format)"
BACKUP_TIMESTAMP="$(read_manifest_value created_at)"
BACKUP_DATABASE="$(read_manifest_value mysql_database)"
[[ "$BACKUP_FORMAT" == "$EXPECTED_FORMAT" ]] || die "unsupported backup format: $BACKUP_FORMAT"
[[ "$BACKUP_TIMESTAMP" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || die 'invalid backup timestamp'
[[ "$BACKUP_DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || die 'unsafe database name in manifest'

MYSQL_RUNNING="$("${COMPOSE[@]}" ps --status running --services mysql)"
[[ "$MYSQL_RUNNING" == 'mysql' ]] || die 'the mysql service must be running before validation or restore'
TARGET_DATABASE="$("${COMPOSE[@]}" exec -T mysql sh -c 'printf "%s" "$MYSQL_DATABASE"')"
[[ "$TARGET_DATABASE" =~ ^[A-Za-z0-9_]+$ ]] || die 'the target MySQL database name is unsafe'
[[ "$TARGET_DATABASE" == "$BACKUP_DATABASE" ]] || die "database mismatch: backup=${BACKUP_DATABASE}, target=${TARGET_DATABASE}"
REDIS_IDS="$("${COMPOSE[@]}" run --rm --no-deps -T --entrypoint sh redis -ceu '
  printf "%s %s\n" "$(id -u redis)" "$(id -g redis)"
')"
read -r REDIS_DATA_UID REDIS_DATA_GID <<<"$REDIS_IDS"
[[ "$REDIS_DATA_UID" =~ ^[0-9]+$ ]] || die 'could not determine the Redis data UID'
[[ "$REDIS_DATA_GID" =~ ^[0-9]+$ ]] || die 'could not determine the Redis data GID'

AI_WORKER_CONTAINER="$("${COMPOSE[@]}" ps --all --quiet ai-worker)"
[[ -n "$AI_WORKER_CONTAINER" ]] || die 'the ai-worker container must already exist; initialize this Compose project first'
AI_IMAGE="$(docker inspect --format '{{.Image}}' "$AI_WORKER_CONTAINER")"
[[ -n "$AI_IMAGE" ]] || die 'could not resolve the deployed AI image'

validate_sqlite_archive() {
  local archive_file="$1"

  docker run --rm -i --network none --entrypoint python "$AI_IMAGE" -c '
import pathlib
import shutil
import sqlite3
import sys
import tempfile

with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory) / "checkpoint.sqlite"
    with path.open("wb") as output:
        shutil.copyfileobj(sys.stdin.buffer, output)
    connection = sqlite3.connect(path)
    try:
        result = connection.execute("PRAGMA integrity_check").fetchone()
        if not result or result[0] != "ok":
            raise SystemExit(f"SQLite integrity check failed: {result}")
    finally:
        connection.close()
' <"$archive_file"
}

validate_directory_archive() {
  local archive_file="$1"
  local label="$2"
  local maximum_bytes="$3"

  docker run --rm -i --network none \
    --env "ARCHIVE_LABEL=${label}" \
    --env "MAX_ARCHIVE_BYTES=${maximum_bytes}" \
    --entrypoint python "$AI_IMAGE" -c '
import os
import pathlib
import shutil
import sys
import tarfile
import tempfile

label = os.environ["ARCHIVE_LABEL"]
maximum_size = int(os.environ["MAX_ARCHIVE_BYTES"])
with tempfile.NamedTemporaryFile(suffix=".tar.gz") as archive_file:
    shutil.copyfileobj(sys.stdin.buffer, archive_file)
    archive_file.flush()
    with tarfile.open(archive_file.name, "r:gz") as archive:
        seen = set()
        files = set()
        file_sizes = {}
        total_size = 0
        for member in archive.getmembers():
            path = pathlib.PurePosixPath(member.name)
            if path.is_absolute() or not path.parts or any(part in ("", ".", "..") for part in path.parts):
                raise SystemExit(f"unsafe {label} archive path: {member.name!r}")
            if member.issym() or member.islnk() or not (member.isfile() or member.isdir()):
                raise SystemExit(f"unsafe {label} archive entry type: {member.name!r}")
            normalized = path.as_posix()
            if normalized in seen:
                raise SystemExit(f"duplicate {label} archive path: {normalized!r}")
            if any(parent.as_posix() in files for parent in path.parents if parent.as_posix() != "."):
                raise SystemExit(f"{label} archive places an entry below a file: {normalized!r}")
            seen.add(normalized)
            if member.isfile():
                if any(existing.startswith(normalized + "/") for existing in seen):
                    raise SystemExit(f"{label} archive replaces a parent directory with a file: {normalized!r}")
                files.add(normalized)
                file_sizes[normalized] = member.size
                total_size += member.size
                if total_size > maximum_size:
                    raise SystemExit(f"{label} archive exceeds its configured restore limit")
        if label == "Redis":
            required = ("dump.rdb", "appendonlydir/appendonly.aof.manifest")
            missing = [name for name in required if file_sizes.get(name, 0) <= 0]
            if missing:
                raise SystemExit(f"Redis persistence files are missing or empty: {missing}")
' <"$archive_file"
}

printf 'Validating checkpoint databases and state archives...\n'
validate_sqlite_archive "${BACKUP_PATH}/story-checkpoints.sqlite"
validate_sqlite_archive "${BACKUP_PATH}/chapter-checkpoints.sqlite"
validate_directory_archive "${BACKUP_PATH}/exports.tar.gz" exports "$MAX_EXPORT_BYTES"
validate_directory_archive "${BACKUP_PATH}/redis-data.tar.gz" Redis "$MAX_REDIS_BYTES"

EXPORT_VOLUME="$(resolve_compose_volume story-forge-exports)"
REDIS_VOLUME="$(resolve_compose_volume story-forge-redis)"
docker volume inspect "$EXPORT_VOLUME" >/dev/null
docker volume inspect "$REDIS_VOLUME" >/dev/null

printf 'Backup %s is valid (created %s, database %s).\n' "$BACKUP_PATH" "$BACKUP_TIMESTAMP" "$BACKUP_DATABASE"

if is_true "$DRY_RUN_VALUE"; then
  printf 'Dry run complete; no application data was changed.\n'
  exit 0
fi

if ! is_true "$FORCE_VALUE"; then
  [[ -t 0 && -t 1 ]] || die 'non-interactive restore requires FORCE=1 or --force'
  printf 'This will replace MySQL, Redis durable state, both checkpoint databases, and all exports.\n'
  printf 'Type RESTORE %s to continue: ' "$TARGET_DATABASE"
  IFS= read -r confirmation
  [[ "$confirmation" == "RESTORE ${TARGET_DATABASE}" ]] || die 'confirmation did not match; nothing was changed'
fi

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
RESTORE_STARTED=0
REDIS_RESTART_REQUIRED=0
REDIS_WAS_RUNNING=0
if grep -qx 'redis' <<<"$RUNNING_SERVICES"; then
  REDIS_WAS_RUNNING=1
fi

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
      printf 'restore: Redis did not become healthy after restart\n' >&2
      return 1
    fi
    REDIS_RESTART_REQUIRED=0
  fi

  ((${#STOPPED_SERVICES[@]} > 0)) || return 0
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
  if ((rc != 0)) && ((RESTORE_STARTED)); then
    printf 'restore: WARNING: restore failed after mutation started; Redis and application services remain stopped; recover from a known-good backup before enabling traffic\n' >&2
    exit "$rc"
  fi
  if ! resume_services; then
    printf 'restore: WARNING: Redis or one or more application services could not be restarted\n' >&2
    rc=1
  fi
  exit "$rc"
}
trap cleanup EXIT INT TERM

for service in "${APP_SERVICES[@]}"; do
  if was_running "$service"; then
    STOPPED_SERVICES+=("$service")
  fi
done

if ((${#STOPPED_SERVICES[@]} > 0)); then
  printf 'Quiescing application writes...\n'
  "${COMPOSE[@]}" stop -t "$STOP_TIMEOUT_SECONDS" "${STOPPED_SERVICES[@]}"
fi

printf 'Stopping Redis before durable-state restore...\n'
if ((REDIS_WAS_RUNNING)); then
  REDIS_RESTART_REQUIRED=1
  "${COMPOSE[@]}" stop -t "$STOP_TIMEOUT_SECONDS" redis
fi

# Recheck the immutable inputs after quiescing and immediately before the
# first destructive operation, closing the validation-to-restore window.
for file in manifest.txt mysql.sql.gz story-checkpoints.sqlite chapter-checkpoints.sqlite exports.tar.gz redis-data.tar.gz; do
  verify_checksum "$file"
done

RESTORE_STARTED=1
# A successful restore always brings Redis back, including recovery from a
# corrupted volume that prevented the original container from starting.
REDIS_RESTART_REQUIRED=1

printf 'Restoring MySQL...\n'
gzip -dc "${BACKUP_PATH}/mysql.sql.gz" | "${COMPOSE[@]}" exec -T mysql sh -ceu '
  exec env MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root
'

restore_sqlite() {
  local service="$1"
  local target_path="$2"
  local archive_file="$3"

  "${COMPOSE[@]}" run --rm --no-deps -T --user 0:0 \
    --entrypoint python "$service" -c '
import os
import pathlib
import shutil
import sqlite3
import sys
import tempfile

target_path = pathlib.Path(sys.argv[1])
uid = int(sys.argv[2])
gid = int(sys.argv[3])
target_path.parent.mkdir(parents=True, exist_ok=True)
os.chown(target_path.parent, uid, gid)
os.chmod(target_path.parent, 0o700)

with tempfile.TemporaryDirectory() as directory:
    source_path = pathlib.Path(directory) / "source.sqlite"
    with source_path.open("wb") as output:
        shutil.copyfileobj(sys.stdin.buffer, output)
    source = sqlite3.connect(source_path)
    try:
        result = source.execute("PRAGMA integrity_check").fetchone()
        if not result or result[0] != "ok":
            raise SystemExit(f"SQLite integrity check failed: {result}")

        temporary_target = target_path.with_name(target_path.name + ".restore")
        for suffix in ("", "-wal", "-shm"):
            candidate = pathlib.Path(str(temporary_target) + suffix)
            if candidate.exists():
                candidate.unlink()
        target = sqlite3.connect(temporary_target)
        try:
            target.execute("PRAGMA journal_mode=DELETE")
            source.backup(target)
            target.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            target.execute("PRAGMA journal_mode=DELETE")
            restored = target.execute("PRAGMA integrity_check").fetchone()
            if not restored or restored[0] != "ok":
                raise SystemExit(f"restored SQLite integrity check failed: {restored}")
        finally:
            target.close()

        for suffix in ("-wal", "-shm"):
            sidecar = pathlib.Path(str(target_path) + suffix)
            if sidecar.exists():
                sidecar.unlink()
        os.chown(temporary_target, uid, gid)
        os.chmod(temporary_target, 0o600)
        os.replace(temporary_target, target_path)
    finally:
        source.close()
' "$target_path" "${DATA_UID:-10001}" "${DATA_GID:-10001}" <"$archive_file"
}

printf 'Restoring story checkpoints...\n'
restore_sqlite ai-worker /data/story/story-checkpoints.sqlite "${BACKUP_PATH}/story-checkpoints.sqlite"

printf 'Restoring chapter checkpoints...\n'
restore_sqlite chapter-worker /data/chapter-checkpoints.sqlite "${BACKUP_PATH}/chapter-checkpoints.sqlite"

restore_directory_archive() {
  local volume_name="$1"
  local archive_file="$2"
  local owner_uid="$3"
  local owner_gid="$4"
  local maximum_bytes="$5"
  local label="$6"

  "${COMPOSE[@]}" run --rm --no-deps -T --user 0:0 \
    -v "${volume_name}:/restore" \
    --env "ARCHIVE_LABEL=${label}" \
    --env "MAX_ARCHIVE_BYTES=${maximum_bytes}" \
    --entrypoint python ai-worker -c '
import os
import pathlib
import shutil
import sys
import tarfile
import tempfile

root = pathlib.Path("/restore")
uid = int(sys.argv[1])
gid = int(sys.argv[2])
label = os.environ["ARCHIVE_LABEL"]
maximum_size = int(os.environ["MAX_ARCHIVE_BYTES"])
os.chown(root, uid, gid)
os.chmod(root, 0o700)

with tempfile.NamedTemporaryFile(suffix=".tar.gz") as archive_file:
    shutil.copyfileobj(sys.stdin.buffer, archive_file)
    archive_file.flush()
    with tarfile.open(archive_file.name, "r:gz") as archive:
        members = archive.getmembers()
        normalized = {}
        files_seen = set()
        file_sizes = {}
        total_size = 0
        for member in members:
            path = pathlib.PurePosixPath(member.name)
            if path.is_absolute() or not path.parts or any(part in ("", ".", "..") for part in path.parts):
                raise SystemExit(f"unsafe {label} archive path: {member.name!r}")
            if member.issym() or member.islnk() or not (member.isfile() or member.isdir()):
                raise SystemExit(f"unsafe {label} archive entry type: {member.name!r}")
            key = path.as_posix()
            if key in normalized:
                raise SystemExit(f"duplicate {label} archive path: {key!r}")
            if any(parent.as_posix() in files_seen for parent in path.parents if parent.as_posix() != "."):
                raise SystemExit(f"{label} archive places an entry below a file: {key!r}")
            normalized[key] = (path, member)
            if member.isfile():
                if any(existing.startswith(key + "/") for existing in normalized):
                    raise SystemExit(f"{label} archive replaces a parent directory with a file: {key!r}")
                files_seen.add(key)
                file_sizes[key] = member.size
                total_size += member.size
                if total_size > maximum_size:
                    raise SystemExit(f"{label} archive exceeds its configured restore limit")

        if label == "Redis":
            required = ("dump.rdb", "appendonlydir/appendonly.aof.manifest")
            missing = [name for name in required if file_sizes.get(name, 0) <= 0]
            if missing:
                raise SystemExit(f"Redis persistence files are missing or empty: {missing}")

        for child in root.iterdir():
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            else:
                child.unlink()

        directories = sorted(
            (item for item in normalized.values() if item[1].isdir()),
            key=lambda item: len(item[0].parts),
        )
        files = sorted(
            (item for item in normalized.values() if item[1].isfile()),
            key=lambda item: item[0].as_posix(),
        )

        for path, member in directories:
            destination = root.joinpath(*path.parts)
            destination.mkdir(parents=True, exist_ok=True)
            os.chmod(destination, member.mode & 0o777)

        for path, member in files:
            destination = root.joinpath(*path.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise SystemExit(f"could not read {label} archive member: {member.name!r}")
            with source, destination.open("wb") as output:
                shutil.copyfileobj(source, output)
            os.chmod(destination, member.mode & 0o666)

for directory, directory_names, file_names in os.walk(root):
    os.chown(directory, uid, gid)
    for name in directory_names:
        os.chown(pathlib.Path(directory) / name, uid, gid)
    for name in file_names:
        os.chown(pathlib.Path(directory) / name, uid, gid)
' "$owner_uid" "$owner_gid" <"$archive_file"
}

printf 'Restoring exports...\n'
restore_directory_archive \
  "$EXPORT_VOLUME" \
  "${BACKUP_PATH}/exports.tar.gz" \
  "${DATA_UID:-10001}" \
  "${DATA_GID:-10001}" \
  "$MAX_EXPORT_BYTES" \
  exports

printf 'Restoring Redis durable state...\n'
restore_directory_archive \
  "$REDIS_VOLUME" \
  "${BACKUP_PATH}/redis-data.tar.gz" \
  "$REDIS_DATA_UID" \
  "$REDIS_DATA_GID" \
  "$MAX_REDIS_BYTES" \
  Redis

printf 'Restore completed from %s.\n' "$BACKUP_PATH"
