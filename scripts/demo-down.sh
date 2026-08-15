#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="$ROOT/.demo"
PID_DIR="$DEMO_DIR/pids"
QUIET=false
[[ "${1:-}" == "--quiet" ]] && QUIET=true

stop_pid() {
  local pid_file="$1"
  local name pid attempt
  name="$(basename "$pid_file" .pid)"
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    [[ "$QUIET" == true ]] || echo "Stopping $name (PID $pid)…"
    kill -TERM "$pid" 2>/dev/null || true
    for attempt in $(seq 1 40); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.25
    done
    if kill -0 "$pid" 2>/dev/null; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
  fi
  rm -f "$pid_file"
}

if [[ -d "$PID_DIR" ]]; then
  # Stop browser-facing processes first, then their upstreams.
  for name in frontend bff orchestrator case tools auth oidc; do
    [[ -f "$PID_DIR/$name.pid" ]] && stop_pid "$PID_DIR/$name.pid"
  done
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] && stop_pid "$pid_file"
  done
fi

if [[ -s "$DEMO_DIR/containers-started" ]] && command -v docker >/dev/null 2>&1; then
  containers=()
  while IFS= read -r container; do [[ -n "$container" ]] && containers+=("$container"); done <"$DEMO_DIR/containers-started"
  if (( ${#containers[@]} > 0 )); then
    [[ "$QUIET" == true ]] || echo "Stopping demo-owned containers: ${containers[*]}…"
    (cd "$ROOT" && docker compose stop "${containers[@]}") >/dev/null 2>&1 || true
  fi
fi

rm -f "$DEMO_DIR/running" "$DEMO_DIR/profile" "$DEMO_DIR/containers-started"
[[ "$QUIET" == true ]] || echo "Argus local demo stopped. Logs and generated local secrets remain under .demo/."
