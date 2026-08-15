#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

cleanup() {
  local status="$?"
  trap - EXIT
  if (( status != 0 )); then
    echo "Drill failed; dedicated Redis logs follow (secrets are not logged):" >&2
    docker compose --profile drill logs --no-color --tail 100 \
      redis-drill-primary redis-drill-replica >&2 || true
  fi
  docker compose --profile drill rm -sf redis-drill-primary redis-drill-replica >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ ! -f infra/tls/generated/ca.key ]]; then
  ./infra/tls/generate-dev-pki.sh
fi
./infra/tls/ensure-dev-drill-pki.sh

export ARGUS_DRILL_REDIS_PASSWORD="${ARGUS_DRILL_REDIS_PASSWORD:-argus-drill-redis-secret}"
export ARGUS_DRILL_CONTAINER_UID="${ARGUS_DRILL_CONTAINER_UID:-$(id -u)}"
export ARGUS_DRILL_CONTAINER_GID="${ARGUS_DRILL_CONTAINER_GID:-$(id -g)}"
docker compose --profile drill rm -sf redis-drill-primary redis-drill-replica >/dev/null 2>&1 || true
docker compose --profile drill up -d --wait --wait-timeout 60 \
  redis-drill-primary redis-drill-replica

set -a
source infra/tls/generated/.env.mtls
set +a
export BFF_REDIS_URL=rediss://localhost:6391
export BFF_REDIS_PASSWORD="$ARGUS_DRILL_REDIS_PASSWORD"
export BFF_ENCRYPTION_PRIMARY_KEY_ID=drill-v1
export BFF_ENCRYPTION_KEYS="drill-v1:$(openssl rand -base64 32)"
export ARGUS_DRILL_PRIMARY_URL=rediss://localhost:6391
export ARGUS_DRILL_REPLICA_URL=rediss://localhost:6392

cd bff
npm run build
npx --no-install tsx scripts/multi-region-auth-drill.ts
