#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="$ROOT/.demo"
PID_DIR="$DEMO_DIR/pids"
QUIET=false
[[ "${1:-}" == "--quiet" ]] && QUIET=true
failed=false

if [[ ! -f "$DEMO_DIR/running" ]]; then
  [[ "$QUIET" == true ]] || echo "Argus local demo is not marked as running."
  exit 1
fi

for name in oidc auth tools case orchestrator bff frontend; do
  pid="$(cat "$PID_DIR/$name.pid" 2>/dev/null || true)"
  if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
    [[ "$QUIET" == true ]] || printf '%-14s up (PID %s)\n' "$name" "$pid"
  else
    [[ "$QUIET" == true ]] || printf '%-14s DOWN\n' "$name"
    failed=true
  fi
done

for item in \
  'OIDC:http://localhost:9091/health' \
  'BFF readiness:http://localhost:3001/ready' \
  'React console:http://localhost:5173/'; do
  label="${item%%:*}"
  url="${item#*:}"
  if curl -fsS "$url" >/dev/null 2>&1; then
    [[ "$QUIET" == true ]] || printf '%-14s reachable\n' "$label"
  else
    [[ "$QUIET" == true ]] || printf '%-14s UNREACHABLE\n' "$label"
    failed=true
  fi
done

[[ "$failed" == false ]]
