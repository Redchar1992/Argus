#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${ENV_FILE:-${ROOT_DIR}/.env.pilot}"

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Pilot env file not found: %s\n' "${ENV_FILE}" >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

: "${PUBLIC_HOST:?PUBLIC_HOST is required}"

COMPOSE=(
  docker compose
  --env-file "${ENV_FILE}"
  -f "${ROOT_DIR}/docker-compose.yml"
  -f "${ROOT_DIR}/docker-compose.pilot.yml"
)

failure=""
for service in mysql redis ai-service ai-worker chapter-worker backend frontend caddy; do
  container_id="$("${COMPOSE[@]}" ps -q "${service}")"
  if [[ -z "${container_id}" ]]; then
    failure="service ${service} has no container"
    break
  fi
  state="$(docker inspect --format '{{.State.Status}}' "${container_id}")"
  if [[ "${state}" != "running" ]]; then
    failure="service ${service} is ${state}"
    break
  fi
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "${container_id}")"
  if [[ -n "${health}" && "${health}" != "healthy" ]]; then
    failure="service ${service} health is ${health}"
    break
  fi
done

if [[ -z "${failure}" ]]; then
  read -r redis_used redis_max request_length event_length chapter_command_length \
    chapter_event_length chapter_dead_letter_length < <(
    "${COMPOSE[@]}" exec -T redis sh -ceu '
      used="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw INFO memory |
        awk -F: '\''$1 == "used_memory" { gsub("\r", "", $2); print $2 }'\'')"
      maximum="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw CONFIG GET maxmemory | tail -n 1)"
      requests="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw XLEN story:workflow:requests)"
      events="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw XLEN story:workflow:events)"
      chapter_commands="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw XLEN story:chapter:commands)"
      chapter_events="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw XLEN story:chapter:events)"
      chapter_dead_letters="$(redis-cli --no-auth-warning -a "$REDIS_PASSWORD" --raw XLEN story:chapter:events:dead-letter)"
      printf "%s %s %s %s %s %s %s\n" "$used" "$maximum" "$requests" "$events" \
        "$chapter_commands" "$chapter_events" "$chapter_dead_letters"
    '
  )
  memory_threshold="${REDIS_MEMORY_ALERT_PERCENT:-90}"
  stream_threshold="${WORKFLOW_STREAM_ALERT_LENGTH:-1000}"
  dead_letter_threshold="${CHAPTER_DEAD_LETTER_ALERT_LENGTH:-0}"
  if [[ ! "${memory_threshold}" =~ ^[1-9][0-9]*$ ]] || ((memory_threshold > 100)) \
    || [[ ! "${stream_threshold}" =~ ^[0-9]+$ ]] \
    || [[ ! "${dead_letter_threshold}" =~ ^[0-9]+$ ]]; then
    failure="health alert thresholds are invalid"
  elif [[ ! "${redis_used}" =~ ^[0-9]+$ || ! "${redis_max}" =~ ^[1-9][0-9]*$ ]]; then
    failure="could not read Redis memory usage"
  elif ((redis_used * 100 >= redis_max * memory_threshold)); then
    failure="Redis memory usage is at least ${memory_threshold}%"
  elif [[ ! "${request_length}" =~ ^[0-9]+$ || ! "${event_length}" =~ ^[0-9]+$ \
    || ! "${chapter_command_length}" =~ ^[0-9]+$ || ! "${chapter_event_length}" =~ ^[0-9]+$ \
    || ! "${chapter_dead_letter_length}" =~ ^[0-9]+$ ]]; then
    failure="could not read Redis stream lengths"
  elif ((request_length > stream_threshold || event_length > stream_threshold \
    || chapter_command_length > stream_threshold || chapter_event_length > stream_threshold)); then
    failure="workflow or chapter stream backlog exceeds ${stream_threshold} records"
  elif ((chapter_dead_letter_length > dead_letter_threshold)); then
    failure="chapter dead-letter stream exceeds ${dead_letter_threshold} records"
  fi
fi

if [[ -z "${failure}" ]] && ! curl --fail --silent --show-error \
  --max-time 10 "https://${PUBLIC_HOST}/api/health" >/dev/null; then
  failure="public health endpoint failed"
fi

if [[ -z "${failure}" ]]; then
  printf 'Story Forge pilot is healthy: https://%s\n' "${PUBLIC_HOST}"
  exit 0
fi

printf 'Story Forge pilot check failed: %s\n' "${failure}" >&2
if [[ -n "${ALERT_WEBHOOK_URL:-}" ]]; then
  escaped="${failure//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  curl --fail --silent --show-error --max-time 10 \
    -H 'Content-Type: application/json' \
    --data "{\"service\":\"story-forge\",\"status\":\"failed\",\"message\":\"${escaped}\"}" \
    "${ALERT_WEBHOOK_URL}" >/dev/null || true
fi
exit 1
