#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_BUILD=false
[[ "${1:-}" == "--skip-build" ]] && SKIP_BUILD=true

for command in docker curl; do
  command -v "$command" >/dev/null 2>&1 || { echo "Missing command: $command" >&2; exit 1; }
done

if [[ "$SKIP_BUILD" == false ]]; then
  mvn -B -q -f "$ROOT/backend/pom.xml" -DskipTests package
fi

CONTAINER="argus-flyway-check-$$"
TMP_DIR="$(mktemp -d)"
PIDS=()

cleanup() {
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
  done
  docker stop "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

docker run -d --rm --name "$CONTAINER" \
  -e POSTGRES_USER=argus \
  -e POSTGRES_PASSWORD=argus \
  -e POSTGRES_DB=argus \
  -p 127.0.0.1::5432 \
  postgres:16.8-alpine >/dev/null

for _ in $(seq 1 60); do
  docker exec "$CONTAINER" pg_isready -U argus -d argus >/dev/null 2>&1 && break
  sleep 0.5
done
docker exec "$CONTAINER" pg_isready -U argus -d argus >/dev/null

PG_PORT="$(docker port "$CONTAINER" 5432/tcp | awk -F: '{print $NF}')"
PG_URL="jdbc:postgresql://127.0.0.1:${PG_PORT}/argus"
APP_PORT="${ARGUS_MIGRATION_CHECK_PORT:-0}"

start_and_check() {
  local module="$1"
  local log_file="$TMP_DIR/$module.log"
  SPRING_PROFILES_ACTIVE=postgres \
    ARGUS_PG_URL="$PG_URL" \
    ARGUS_PG_USER=argus \
    ARGUS_PG_PASSWORD=argus \
    ARGUS_JWT_SECRET=argus-postgres-validation-secret-at-least-32-bytes \
    java -jar "$ROOT/backend/$module/target/$module-0.1.0.jar" \
      --server.port="$APP_PORT" >"$log_file" 2>&1 &
  local pid=$!
  PIDS+=("$pid")

  for _ in $(seq 1 120); do
    local actual_port="$APP_PORT"
    if [[ "$actual_port" == "0" ]]; then
      actual_port="$(sed -n 's/.*Tomcat started on port \([0-9][0-9]*\).*/\1/p' "$log_file" | tail -n 1)"
    fi
    if [[ -n "$actual_port" ]] \
      && curl -fsS "http://127.0.0.1:$actual_port/actuator/health" >/dev/null 2>&1; then
      echo "$module: Flyway migrated and Hibernate validated"
      kill "$pid" >/dev/null 2>&1 || true
      wait "$pid" >/dev/null 2>&1 || true
      return
    fi
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      cat "$log_file" >&2
      return 1
    fi
    sleep 0.5
  done

  cat "$log_file" >&2
  echo "$module did not become healthy" >&2
  return 1
}

start_and_check auth-service
start_and_check screening-tools-service
start_and_check case-service

actual="$({
  docker exec "$CONTAINER" psql -U argus -d argus -Atc \
    "select table_schema || ':' || count(*) from information_schema.tables where table_schema in ('auth','tools','cases') group by table_schema order by table_schema;"
} | tr -d '\r')"
expected=$'auth:5\ncases:4\ntools:4'
if [[ "$actual" != "$expected" ]]; then
  printf 'Unexpected migrated table counts:\n%s\n' "$actual" >&2
  exit 1
fi
printf '%s\n' "$actual"
