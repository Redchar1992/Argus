#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

bash -n \
  "${ROOT_DIR}/backup.sh" \
  "${ROOT_DIR}/restore.sh" \
  "${ROOT_DIR}/test-backup-restore.sh"

"${ROOT_DIR}/restore.sh" --help >/dev/null

if "${ROOT_DIR}/restore.sh" >/dev/null 2>&1; then
  printf 'expected restore without a backup path to fail\n' >&2
  exit 1
fi

TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT INT TERM
mkdir -p "${TEMP_DIR}/real-backup"
ln -s "${TEMP_DIR}/real-backup" "${TEMP_DIR}/linked-backup"
if "${ROOT_DIR}/restore.sh" --dry-run "${TEMP_DIR}/linked-backup" >/dev/null 2>&1; then
  printf 'expected restore to reject a symlinked backup directory\n' >&2
  exit 1
fi

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

write_checksum_list() {
  local directory="$1"
  (
    cd "$directory"
    for file in manifest.txt mysql.sql.gz story-checkpoints.sqlite chapter-checkpoints.sqlite exports.tar.gz redis-data.tar.gz; do
      printf '%s  %s\n' "$(sha256_file "$file")" "$file"
    done >SHA256SUMS
  )
}

# Exercise the complete non-mutating validation path without requiring a
# running Docker daemon. Runtime image validation is represented by a strict
# fake that accepts only the Docker calls used before the dry-run exit.
FIXTURE_DIR="${TEMP_DIR}/fixture"
FAKE_BIN="${TEMP_DIR}/bin"
mkdir -p "$FIXTURE_DIR" "$FAKE_BIN"
printf 'format=story-forge-backup-v2\ncreated_at=20260803T000000Z\nmysql_database=story_forge\n' >"${FIXTURE_DIR}/manifest.txt"
printf 'mysql fixture\n' | gzip >"${FIXTURE_DIR}/mysql.sql.gz"
python3 - "$FIXTURE_DIR" <<'PY'
import io
import pathlib
import sqlite3
import sys
import tarfile

root = pathlib.Path(sys.argv[1])
for name in ("story-checkpoints.sqlite", "chapter-checkpoints.sqlite"):
    connection = sqlite3.connect(root / name)
    connection.execute("CREATE TABLE checkpoint (id INTEGER PRIMARY KEY, value TEXT)")
    connection.execute("INSERT INTO checkpoint(value) VALUES ('fixture')")
    connection.commit()
    connection.close()

archives = {
    "exports.tar.gz": [("story.txt", b"export fixture\n")],
    "redis-data.tar.gz": [
        ("dump.rdb", b"redis rdb fixture\n"),
        ("appendonlydir/appendonly.aof.manifest", b"file appendonly.aof.1.incr.aof seq 1 type i\n"),
        ("appendonlydir/appendonly.aof.1.incr.aof", b"redis aof fixture\n"),
    ],
}
for archive_name, members in archives.items():
    with tarfile.open(root / archive_name, "w:gz") as archive:
        for member_name, payload in members:
            member = tarfile.TarInfo(member_name)
            member.size = len(payload)
            member.mode = 0o600
            archive.addfile(member, io.BytesIO(payload))
PY
write_checksum_list "$FIXTURE_DIR"
touch "${TEMP_DIR}/fake.env"

cat >"${FAKE_BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${FAKE_DOCKER_LOG:-}" ]]; then
  printf '%s\n' "$*" >>"$FAKE_DOCKER_LOG"
fi

case "${1:-}" in
  compose)
    arguments="$*"
    case "$arguments" in
      *' config --quiet') exit 0 ;;
      *' config --services') printf 'mysql\nredis\nai-service\nai-worker\nchapter-worker\nbackend\nfrontend\n' ;;
      *' ps --status running --services mysql') printf 'mysql\n' ;;
      *' ps --status running --services redis') printf 'redis\n' ;;
      *' ps --status running --services')
        if [[ "${FAKE_REDIS_STOPPED:-0}" == '1' ]]; then
          printf 'mysql\nai-service\nai-worker\nchapter-worker\nbackend\nfrontend\n'
        else
          printf 'mysql\nredis\nai-service\nai-worker\nchapter-worker\nbackend\nfrontend\n'
        fi
        ;;
      *' exec -T mysql sh -c '*) printf 'story_forge' ;;
      *' exec -T mysql sh -ceu '*) printf '%s\n' 'CREATE DATABASE story_forge;' ;;
      *' exec -T redis sh -c '*'id -u redis'*) printf '999' ;;
      *' exec -T redis sh -c '*'id -g redis'*) printf '999' ;;
      *' exec -T redis sh -ceu '*' SAVE'*) printf 'OK\n' ;;
      *' exec -T redis sh -ceu '*' ping'*) printf 'PONG\n' ;;
      *' ps --all --quiet ai-worker') printf 'fake-ai-worker-container\n' ;;
      *' stop -t '* | *' start '*) exit 0 ;;
      *' run --rm --no-deps -T '*)
        if [[ "$arguments" == *'--entrypoint sh redis -ceu '* ]]; then
          printf '999 999\n'
          exit 0
        fi
        backup_mount=''
        previous=''
        for argument in "$@"; do
          if [[ "$previous" == '-v' && "$argument" == *':/backup' ]]; then
            backup_mount="${argument%:/backup}"
          fi
          previous="$argument"
        done
        if [[ -z "$backup_mount" && "${FAKE_RESTORE_SUCCESS:-0}" == '1' ]]; then
          cat >/dev/null
          exit 0
        fi
        [[ -n "$backup_mount" ]] || { printf 'fake backup mount missing\n' >&2; exit 92; }
        [[ -n "${FAKE_FIXTURE_DIR:-}" ]] || { printf 'fake fixture directory missing\n' >&2; exit 94; }
        case "$arguments" in
          *'/backup/story-checkpoints.sqlite'*) cp "${FAKE_FIXTURE_DIR}/story-checkpoints.sqlite" "${backup_mount}/story-checkpoints.sqlite" ;;
          *'/backup/chapter-checkpoints.sqlite'*) cp "${FAKE_FIXTURE_DIR}/chapter-checkpoints.sqlite" "${backup_mount}/chapter-checkpoints.sqlite" ;;
          *'redis-data.tar.gz'*) cp "${FAKE_FIXTURE_DIR}/redis-data.tar.gz" "${backup_mount}/redis-data.tar.gz" ;;
          *'exports.tar.gz'*) cp "${FAKE_FIXTURE_DIR}/exports.tar.gz" "${backup_mount}/exports.tar.gz" ;;
          *) printf 'unexpected fake compose run: %s\n' "$arguments" >&2; exit 93 ;;
        esac
        ;;
      *' config')
        printf 'volumes:\n  story-forge-exports:\n    name: fake_story-forge-exports\n  story-forge-redis:\n    name: fake_story-forge-redis\n'
        ;;
      *) printf 'unexpected fake docker compose call: %s\n' "$arguments" >&2; exit 90 ;;
    esac
    ;;
  inspect)
    printf 'fake-ai-image\n'
    ;;
  run)
    shift
    python_code=''
    while (($# > 0)); do
      case "$1" in
        --env)
          export "$2"
          shift 2
          ;;
        --entrypoint)
          shift 2
          ;;
        -c)
          python_code="$2"
          shift 2
          ;;
        *) shift ;;
      esac
    done
    [[ -n "$python_code" ]]
    python3 -c "$python_code"
    ;;
  volume)
    [[ "${2:-}" == inspect ]]
    [[ "${3:-}" == fake_story-forge-exports || "${3:-}" == fake_story-forge-redis ]]
    ;;
  *)
    printf 'unexpected fake docker call: %s\n' "$*" >&2
    exit 91
    ;;
esac
EOF
chmod +x "${FAKE_BIN}/docker"

PATH="${FAKE_BIN}:${PATH}" \
ENV_FILE="${TEMP_DIR}/fake.env" \
  "${ROOT_DIR}/restore.sh" --dry-run "$FIXTURE_DIR" >/dev/null

mv "${FIXTURE_DIR}/redis-data.tar.gz" "${FIXTURE_DIR}/redis-data.tar.gz.missing"
if PATH="${FAKE_BIN}:${PATH}" ENV_FILE="${TEMP_DIR}/fake.env" \
  "${ROOT_DIR}/restore.sh" --dry-run "$FIXTURE_DIR" >/dev/null 2>&1; then
  printf 'expected restore to reject a backup without Redis durable state\n' >&2
  exit 1
fi
mv "${FIXTURE_DIR}/redis-data.tar.gz.missing" "${FIXTURE_DIR}/redis-data.tar.gz"

cp "${FIXTURE_DIR}/redis-data.tar.gz" "${FIXTURE_DIR}/redis-data.tar.gz.valid"
python3 - "${FIXTURE_DIR}/redis-data.tar.gz" <<'PY'
import io
import sys
import tarfile

with tarfile.open(sys.argv[1], "w:gz") as archive:
    payload = b"unsafe\n"
    member = tarfile.TarInfo("../../outside")
    member.size = len(payload)
    archive.addfile(member, io.BytesIO(payload))
PY
write_checksum_list "$FIXTURE_DIR"
if PATH="${FAKE_BIN}:${PATH}" ENV_FILE="${TEMP_DIR}/fake.env" \
  "${ROOT_DIR}/restore.sh" --dry-run "$FIXTURE_DIR" >/dev/null 2>&1; then
  printf 'expected restore to reject Redis archive path traversal\n' >&2
  exit 1
fi
python3 - "${FIXTURE_DIR}/redis-data.tar.gz" <<'PY'
import io
import sys
import tarfile

with tarfile.open(sys.argv[1], "w:gz") as archive:
    payload = b"not durable state\n"
    member = tarfile.TarInfo("unrelated.txt")
    member.size = len(payload)
    archive.addfile(member, io.BytesIO(payload))
PY
write_checksum_list "$FIXTURE_DIR"
if PATH="${FAKE_BIN}:${PATH}" ENV_FILE="${TEMP_DIR}/fake.env" \
  "${ROOT_DIR}/restore.sh" --dry-run "$FIXTURE_DIR" >/dev/null 2>&1; then
  printf 'expected restore to require Redis RDB and AOF manifest files\n' >&2
  exit 1
fi
mv "${FIXTURE_DIR}/redis-data.tar.gz.valid" "${FIXTURE_DIR}/redis-data.tar.gz"
write_checksum_list "$FIXTURE_DIR"

if PATH="${FAKE_BIN}:${PATH}" \
  ENV_FILE="${TEMP_DIR}/fake.env" \
  RESTORE_MAX_REDIS_BYTES=1 \
    "${ROOT_DIR}/restore.sh" --dry-run "$FIXTURE_DIR" >/dev/null 2>&1; then
  printf 'expected restore to enforce the Redis uncompressed-size limit\n' >&2
  exit 1
fi

CONFIRMATION_LOG="${TEMP_DIR}/confirmation-required.log"
if PATH="${FAKE_BIN}:${PATH}" \
  ENV_FILE="${TEMP_DIR}/fake.env" \
  FAKE_DOCKER_LOG="$CONFIRMATION_LOG" \
    "${ROOT_DIR}/restore.sh" "$FIXTURE_DIR" </dev/null >/dev/null 2>&1; then
  printf 'expected a non-interactive restore without FORCE=1 to fail\n' >&2
  exit 1
fi
if grep -q ' stop ' "$CONFIRMATION_LOG"; then
  printf 'restore changed service state before explicit confirmation\n' >&2
  exit 1
fi

FAKE_DOCKER_LOG="${TEMP_DIR}/restore-failure.log"
if PATH="${FAKE_BIN}:${PATH}" \
  ENV_FILE="${TEMP_DIR}/fake.env" \
  FAKE_DOCKER_LOG="$FAKE_DOCKER_LOG" \
  FORCE=1 \
    "${ROOT_DIR}/restore.sh" "$FIXTURE_DIR" >/dev/null 2>&1; then
  printf 'expected the simulated post-mutation restore failure\n' >&2
  exit 1
fi
if grep -q ' start ' "$FAKE_DOCKER_LOG"; then
  printf 'restore restarted application services after a post-mutation failure\n' >&2
  exit 1
fi

SUCCESS_LOG="${TEMP_DIR}/restore-success.log"
PATH="${FAKE_BIN}:${PATH}" \
ENV_FILE="${TEMP_DIR}/fake.env" \
FAKE_DOCKER_LOG="$SUCCESS_LOG" \
FAKE_RESTORE_SUCCESS=1 \
FORCE=1 \
  "${ROOT_DIR}/restore.sh" "$FIXTURE_DIR" >/dev/null
grep -q ' stop -t .* redis' "$SUCCESS_LOG"
grep -q 'fake_story-forge-redis:/restore' "$SUCCESS_LOG"
grep -q ' start redis' "$SUCCESS_LOG"

STOPPED_REDIS_LOG="${TEMP_DIR}/restore-stopped-redis.log"
PATH="${FAKE_BIN}:${PATH}" \
ENV_FILE="${TEMP_DIR}/fake.env" \
FAKE_DOCKER_LOG="$STOPPED_REDIS_LOG" \
FAKE_RESTORE_SUCCESS=1 \
FAKE_REDIS_STOPPED=1 \
FORCE=1 \
  "${ROOT_DIR}/restore.sh" "$FIXTURE_DIR" >/dev/null
if grep -q ' stop -t .* redis' "$STOPPED_REDIS_LOG"; then
  printf 'restore tried to stop an already stopped Redis service\n' >&2
  exit 1
fi
grep -q ' start redis' "$STOPPED_REDIS_LOG"

cp "${FIXTURE_DIR}/SHA256SUMS" "${FIXTURE_DIR}/SHA256SUMS.valid"
printf '%064d  ../../outside\n' 0 >>"${FIXTURE_DIR}/SHA256SUMS"
if PATH="${FAKE_BIN}:${PATH}" ENV_FILE="${TEMP_DIR}/fake.env" \
  "${ROOT_DIR}/restore.sh" --dry-run "$FIXTURE_DIR" >/dev/null 2>&1; then
  printf 'expected restore to reject an unexpected checksum path\n' >&2
  exit 1
fi
mv "${FIXTURE_DIR}/SHA256SUMS.valid" "${FIXTURE_DIR}/SHA256SUMS"

BACKUP_OUTPUT="${TEMP_DIR}/backup-output"
BACKUP_LOG="${TEMP_DIR}/backup.log"
PATH="${FAKE_BIN}:${PATH}" \
ENV_FILE="${TEMP_DIR}/fake.env" \
BACKUP_DIR="$BACKUP_OUTPUT" \
FAKE_DOCKER_LOG="$BACKUP_LOG" \
FAKE_FIXTURE_DIR="$FIXTURE_DIR" \
  "${ROOT_DIR}/backup.sh" >/dev/null

grep -q ' SAVE' "$BACKUP_LOG"
grep -q ' stop -t .* redis' "$BACKUP_LOG"
grep -q 'redis-data.tar.gz' "$BACKUP_LOG"
grep -q ' start redis' "$BACKUP_LOG"

GENERATED_BACKUP="$(find "$BACKUP_OUTPUT" -mindepth 1 -maxdepth 1 -type d ! -name '.*' -print)"
[[ -n "$GENERATED_BACKUP" && "$(printf '%s\n' "$GENERATED_BACKUP" | wc -l | tr -d ' ')" == '1' ]]
for file in manifest.txt SHA256SUMS mysql.sql.gz story-checkpoints.sqlite chapter-checkpoints.sqlite exports.tar.gz redis-data.tar.gz; do
  [[ -f "${GENERATED_BACKUP}/${file}" ]]
done

PATH="${FAKE_BIN}:${PATH}" \
ENV_FILE="${TEMP_DIR}/fake.env" \
  "${ROOT_DIR}/restore.sh" --dry-run "$GENERATED_BACKUP" >/dev/null

if command -v shellcheck >/dev/null 2>&1; then
  shellcheck \
    "${ROOT_DIR}/backup.sh" \
    "${ROOT_DIR}/restore.sh" \
    "${ROOT_DIR}/test-backup-restore.sh"
else
  printf 'shellcheck not installed; syntax and safety smoke checks still ran\n'
fi

if docker compose version >/dev/null 2>&1; then
  MYSQL_PASSWORD=test-mysql-password \
  MYSQL_ROOT_PASSWORD=test-root-password \
  REDIS_PASSWORD=test-redis-password \
  AI_INTERNAL_API_KEY=test-ai-key \
  JWT_SECRET=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    docker compose \
      --env-file "${ROOT_DIR}/.env.example" \
      -f "${ROOT_DIR}/docker-compose.yml" \
      config --quiet
fi

printf 'backup/restore static and safety checks passed\n'
