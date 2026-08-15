#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEMO_DIR="$ROOT/.demo"
PID_DIR="$DEMO_DIR/pids"
LOG_DIR="$DEMO_DIR/logs"
CONTAINER_FILE="$DEMO_DIR/containers-started"
PROFILE="full"
SKIP_BUILD=false

usage() {
  cat <<'EOF'
Usage: ./scripts/demo-up.sh [--full|--lite] [--skip-build]

  --full        Secure local demo (default): mTLS auth, TLS/ACL Redis,
                encrypted shared Sessions, Prometheus, mock external IdP.
  --lite        No-Docker fallback: HTTP auth, memory Sessions, no Prometheus.
  --skip-build  Reuse existing Java jars and Node dependencies/build output.
EOF
}

for argument in "$@"; do
  case "$argument" in
    --full) PROFILE="full" ;;
    --lite) PROFILE="lite" ;;
    --skip-build) SKIP_BUILD=true ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $argument" >&2; usage >&2; exit 2 ;;
  esac
done

mkdir -p "$PID_DIR" "$LOG_DIR"
umask 077

if [[ -f "$DEMO_DIR/running" ]]; then
  alive=false
  for pid_file in "$PID_DIR"/*.pid; do
    [[ -e "$pid_file" ]] || continue
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then alive=true; break; fi
  done
  if [[ "$alive" == true ]]; then
    echo "Argus demo is already running. Use ./scripts/demo-status.sh or ./scripts/demo-down.sh."
    exit 0
  fi
  "$ROOT/scripts/demo-down.sh" >/dev/null 2>&1 || true
fi

: >"$CONTAINER_FILE"
START_SUCCEEDED=false

failure_cleanup() {
  status=$?
  if [[ "$START_SUCCEEDED" != true ]]; then
    echo >&2
    echo "Demo startup failed. Recent logs:" >&2
    for log in "$LOG_DIR"/*.log; do
      [[ -e "$log" ]] || continue
      echo "--- ${log#$ROOT/}" >&2
      tail -n 35 "$log" >&2 || true
    done
    "$ROOT/scripts/demo-down.sh" --quiet >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap failure_cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 1; }
}

require_command java
require_command mvn
require_command node
require_command npm
require_command curl
require_command openssl
require_command lsof
if [[ "$PROFILE" == full ]]; then
  require_command docker
  docker info >/dev/null 2>&1 || { echo "Docker is not running. Use --lite or start Docker Desktop." >&2; exit 1; }
fi

java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
node_major="$(node -p 'process.versions.node.split(".")[0]')"
[[ "$java_major" == 17 ]] || { echo "Java 17 is required; found Java $java_major" >&2; exit 1; }
(( node_major >= 20 )) || { echo "Node 20+ is required; found $(node -v)" >&2; exit 1; }

for port in 3001 5173 8081 8082 8083 8084 9091; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Port $port is already in use by a process not owned by this demo." >&2
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2 || true
    exit 1
  fi
done

ensure_node_workspace() {
  local directory="$1"
  if [[ ! -d "$directory/node_modules" ]]; then
    (cd "$directory" && npm ci)
  fi
}

if [[ "$SKIP_BUILD" == false ]]; then
  echo "[1/7] Building Java services…"
  mvn -q -f "$ROOT/backend/pom.xml" -DskipTests package
  echo "[2/7] Installing and building the Node BFF…"
  ensure_node_workspace "$ROOT/bff"
  (cd "$ROOT/bff" && npm run build)
  echo "[3/7] Installing and type-checking the React console…"
  ensure_node_workspace "$ROOT/frontend/analyst-console"
  (cd "$ROOT/frontend/analyst-console" && npm run build)
else
  for jar in auth-service screening-tools-service case-service agent-orchestrator-service; do
    [[ -f "$ROOT/backend/$jar/target/$jar-0.1.0.jar" ]] \
      || { echo "Missing $jar jar; rerun without --skip-build." >&2; exit 1; }
  done
  [[ -f "$ROOT/bff/dist/server.js" ]] || { echo "Missing BFF build; rerun without --skip-build." >&2; exit 1; }
  ensure_node_workspace "$ROOT/bff"
  ensure_node_workspace "$ROOT/frontend/analyst-console"
fi

container_running() {
  [[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || true)" == true ]]
}

record_container_if_new() {
  local service="$1" container="$2"
  if ! container_running "$container"; then echo "$service" >>"$CONTAINER_FILE"; fi
}

unset ARGUS_AUTH_TLS_ENABLED ARGUS_AUTH_TLS_KEY_STORE ARGUS_AUTH_TLS_KEY_STORE_PASSWORD \
  ARGUS_AUTH_TLS_TRUST_STORE ARGUS_AUTH_TLS_TRUST_STORE_PASSWORD ARGUS_AUTH_TLS_CLIENT_AUTH \
  BFF_AUTH_MTLS_ENABLED BFF_AUTH_TLS_CA_FILE BFF_AUTH_TLS_CERT_FILE BFF_AUTH_TLS_KEY_FILE \
  BFF_AUTH_TLS_SERVER_NAME BFF_REDIS_TLS_CA_FILE BFF_REDIS_TLS_CERT_FILE \
  BFF_REDIS_TLS_KEY_FILE BFF_REDIS_TLS_SERVER_NAME BFF_REDIS_USERNAME BFF_REDIS_PASSWORD

if [[ "$PROFILE" == full ]]; then
  echo "[4/7] Preparing short-lived development PKI and secure Redis…"
  if [[ ! -f "$ROOT/infra/tls/generated/.env.mtls" ]]; then
    "$ROOT/infra/tls/generate-dev-pki.sh"
  fi
  openssl x509 -checkend 3600 -noout -in "$ROOT/infra/tls/generated/auth-server.crt" \
    || { echo "Development certificate is expired or nearly expired. Regenerate infra/tls/generated deliberately." >&2; exit 1; }
  "$ROOT/infra/tls/ensure-dev-monitoring-pki.sh" >/dev/null
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/infra/tls/generated/.env.mtls"
  set +a
  record_container_if_new redis-secure argus-redis-secure
  (cd "$ROOT" && docker compose --profile security up -d --wait --wait-timeout 60 redis-secure)
  export BFF_SESSION_STORE=redis
  if [[ ! -f "$DEMO_DIR/secrets.env" ]]; then
    printf 'BFF_DEMO_ENCRYPTION_KEY=%q\n' "$(openssl rand -base64 32)" >"$DEMO_DIR/secrets.env"
    chmod 600 "$DEMO_DIR/secrets.env"
  fi
  # shellcheck disable=SC1091
  source "$DEMO_DIR/secrets.env"
  export BFF_ENCRYPTION_PRIMARY_KEY_ID=demo-v1
  export BFF_ENCRYPTION_KEYS="demo-v1:$BFF_DEMO_ENCRYPTION_KEY"
else
  echo "[4/7] Using the no-Docker lite profile (memory Session store, plain HTTP auth)."
  export ARGUS_AUTH_TLS_ENABLED=false
  export ARGUS_AUTH_URL=http://127.0.0.1:8081
  export BFF_AUTH_MTLS_ENABLED=false
  export BFF_SESSION_STORE=memory
  unset BFF_REDIS_URL BFF_ENCRYPTION_PRIMARY_KEY_ID BFF_ENCRYPTION_KEYS
fi

export ARGUS_REGION=local-demo
export ARGUS_JWT_SECRET=argus-local-demo-jwt-secret-change-before-production-2026
export ARGUS_IDENTITY_PRIMARY_KEY_ID=demo-v1
export ARGUS_IDENTITY_KEYS=demo-v1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=
export ARGUS_RECOVERY_PEPPER=YXJndXMtcmVjb3ZlcnktZGV2LXBlcHBlci1rZXktdjE=
export ARGUS_INTERNAL_BFF_SECRET=argus-local-demo-internal-bff-secret-2026
export ARGUS_OIDC_ENABLED=true
export ARGUS_OIDC_ISSUER=http://localhost:9091
export ARGUS_OIDC_AUDIENCE=argus-web
export ARGUS_LLM_PROVIDER=local
export ARGUS_TRACE_STORE=memory
export ARGUS_TOOLS_URL=http://127.0.0.1:8083
export ARGUS_CASE_URL=http://127.0.0.1:8084
export ARGUS_CASE_MIRROR=true
export ARGUS_INVESTIGATION_URL=http://127.0.0.1:8082
export BFF_HOST=127.0.0.1
export BFF_PORT=3001
export BFF_ALLOWED_ORIGINS=http://localhost:5173
export BFF_COOKIE_SECURE=false
export BFF_MOCK_UPSTREAM=false
export BFF_OIDC_ENABLED=true
export BFF_OIDC_ISSUER=http://localhost:9091
export BFF_OIDC_CLIENT_ID=argus-web
export BFF_OIDC_REDIRECT_URI=http://localhost:5173/bff/auth/oidc/callback
export BFF_OIDC_SCOPES='openid profile email'
export BFF_PASSKEY_ENABLED=true
export BFF_WEBAUTHN_RP_ID=localhost
export BFF_WEBAUTHN_ORIGIN=http://localhost:5173
export BFF_METRICS_ENABLED=true
export BFF_LOGGER=true
export ARGUS_MOCK_OIDC_PORT=9091
export ARGUS_MOCK_OIDC_CLIENT_ID=argus-web
export ARGUS_MOCK_OIDC_REDIRECT_URI=http://localhost:5173/bff/auth/oidc/callback

start_process() {
  local name="$1"; shift
  nohup "$@" >"$LOG_DIR/$name.log" 2>&1 </dev/null &
  local pid=$!
  printf '%s\n' "$pid" >"$PID_DIR/$name.pid"
}

wait_for() {
  local label="$1"; shift
  local attempt
  for attempt in $(seq 1 120); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 0.5
  done
  echo "Timed out waiting for $label" >&2
  return 1
}

echo "[5/7] Starting mock IdP and real Java services…"
start_process oidc node "$ROOT/bff/scripts/local-oidc-provider.mjs"
wait_for "local OIDC provider" curl -fsS http://localhost:9091/health

start_process auth java -jar "$ROOT/backend/auth-service/target/auth-service-0.1.0.jar"
start_process tools java -jar "$ROOT/backend/screening-tools-service/target/screening-tools-service-0.1.0.jar"
start_process case java -jar "$ROOT/backend/case-service/target/case-service-0.1.0.jar"
start_process orchestrator java -jar "$ROOT/backend/agent-orchestrator-service/target/agent-orchestrator-service-0.1.0.jar"

if [[ "$PROFILE" == full ]]; then
  wait_for "auth-service over mTLS" curl -fsS \
    --cacert "$ROOT/infra/tls/generated/ca.crt" \
    --cert "$ROOT/infra/tls/generated/bff-auth-client.crt" \
    --key "$ROOT/infra/tls/generated/bff-auth-client.key" \
    https://localhost:8081/actuator/health/readiness
else
  wait_for "auth-service" curl -fsS http://127.0.0.1:8081/actuator/health/readiness
fi
wait_for "screening-tools" curl -fsS http://127.0.0.1:8083/actuator/health
wait_for "case-service" curl -fsS http://127.0.0.1:8084/actuator/health
wait_for "agent-orchestrator" curl -fsS http://127.0.0.1:8082/actuator/health

echo "[6/7] Starting the real Node BFF and React console…"
start_process bff node "$ROOT/bff/dist/server.js"
wait_for "identity BFF" curl -fsS http://127.0.0.1:3001/ready

export VITE_BFF_TARGET=http://127.0.0.1:3001
export VITE_OIDC_ENABLED=true
export VITE_LOCAL_DEMO=true
start_process frontend "$ROOT/frontend/analyst-console/node_modules/.bin/vite" \
  "$ROOT/frontend/analyst-console" --host localhost
wait_for "React analyst console" curl -fsS http://localhost:5173/

if [[ "$PROFILE" == full ]]; then
  echo "[7/7] Starting Prometheus with an mTLS auth-service scrape…"
  record_container_if_new prometheus argus-prometheus
  export ARGUS_PROMETHEUS_CONFIG=./infra/monitoring/prometheus-mtls.yml
  export ARGUS_PROMETHEUS_UID="$(id -u)"
  export ARGUS_PROMETHEUS_GID="$(id -g)"
  (cd "$ROOT" && docker compose --profile monitoring up -d --wait --wait-timeout 60 prometheus)
  wait_for "Prometheus" curl -fsS http://localhost:9090/-/ready
else
  echo "[7/7] Prometheus skipped in lite mode."
fi

printf '%s\n' "$PROFILE" >"$DEMO_DIR/profile"
date -u +'%Y-%m-%dT%H:%M:%SZ' >"$DEMO_DIR/running"
START_SUCCEEDED=true
trap - EXIT

cat <<EOF

Argus local demo is ready.

  Analyst console: http://localhost:5173
  Password:        analyst / analyst12345
  Admin:           admin / admin12345
  Mock OIDC:       click “Continue with OIDC” (clearly marked local mock)
  BFF health:      http://localhost:3001/health
  BFF metrics:     http://localhost:3001/metrics
$(if [[ "$PROFILE" == full ]]; then printf '  Prometheus:      http://localhost:9090\n'; fi)

Real locally: password/bcrypt, JWT/RBAC, BFF Session, CSRF, TOTP/recovery,
WebAuthn verification and the agent loop/tools/audit.
$(if [[ "$PROFILE" == full ]]; then printf 'Full-profile controls: authenticated mTLS, encrypted shared Redis and Prometheus metrics.'; else printf 'Lite-profile substitutions: plain HTTP auth transport, in-process Session state and no Prometheus.'; fi)
Mocked/synthetic: the external OIDC account source, on-chain/provider fixtures,
and the optional external LLM (the deterministic local agent is used).

Walkthrough: docs/local-demo.md
Verify:      ./scripts/demo-verify.sh
Stop:        ./scripts/demo-down.sh
Logs:        .demo/logs/
EOF
