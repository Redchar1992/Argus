#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/demo-status.sh"

echo
echo "Running real-stack Playwright demo journeys…"
cd "$ROOT/frontend/analyst-console"
npm run test:e2e:demo
