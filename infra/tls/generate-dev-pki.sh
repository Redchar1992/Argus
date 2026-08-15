#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ARGUS_DEV_PKI_DIR:-$ROOT/infra/tls/generated}"
PASSWORD="${ARGUS_DEV_KEYSTORE_PASSWORD:-argus-dev-changeit}"

if [[ -e "$OUT/ca.key" ]]; then
  echo "Development PKI already exists at $OUT; remove it explicitly before regenerating." >&2
  exit 1
fi

umask 077
mkdir -p "$OUT"

openssl genrsa -out "$OUT/ca.key" 3072 >/dev/null 2>&1
openssl req -x509 -new -sha256 -days 30 \
  -key "$OUT/ca.key" -out "$OUT/ca.crt" \
  -subj '/CN=Argus Development CA/O=Argus Local Development' >/dev/null 2>&1

issue_certificate() {
  local name="$1" common_name="$2" usage="$3" san="$4"
  openssl genrsa -out "$OUT/$name.key" 3072 >/dev/null 2>&1
  openssl req -new -sha256 -key "$OUT/$name.key" -out "$OUT/$name.csr" \
    -subj "/CN=$common_name/O=Argus Local Development" >/dev/null 2>&1
  cat >"$OUT/$name.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=$usage
subjectAltName=$san
EOF
  openssl x509 -req -sha256 -days 14 -in "$OUT/$name.csr" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -extfile "$OUT/$name.ext" -out "$OUT/$name.crt" >/dev/null 2>&1
  rm -f "$OUT/$name.csr" "$OUT/$name.ext"
}

issue_certificate auth-server auth-service serverAuth 'DNS:localhost,DNS:auth-service,IP:127.0.0.1'
issue_certificate bff-auth-client identity-bff clientAuth 'DNS:identity-bff'
issue_certificate redis-server redis serverAuth 'DNS:localhost,DNS:redis,DNS:redis-secure,IP:127.0.0.1'
issue_certificate bff-redis-client identity-bff-redis clientAuth 'DNS:identity-bff'

openssl pkcs12 -export -name argus-auth-service \
  -inkey "$OUT/auth-server.key" -in "$OUT/auth-server.crt" -certfile "$OUT/ca.crt" \
  -out "$OUT/auth-server.p12" -passout "pass:$PASSWORD" >/dev/null 2>&1
keytool -importcert -noprompt -storetype PKCS12 -alias argus-development-ca \
  -file "$OUT/ca.crt" -keystore "$OUT/auth-trust.p12" -storepass "$PASSWORD" >/dev/null 2>&1

cat >"$OUT/.env.mtls" <<EOF
# Generated local-only values. Source this file; never commit it.
ARGUS_AUTH_TLS_ENABLED=true
ARGUS_AUTH_TLS_KEY_STORE=file:$OUT/auth-server.p12
ARGUS_AUTH_TLS_KEY_STORE_PASSWORD=$PASSWORD
ARGUS_AUTH_TLS_TRUST_STORE=file:$OUT/auth-trust.p12
ARGUS_AUTH_TLS_TRUST_STORE_PASSWORD=$PASSWORD
ARGUS_AUTH_TLS_CLIENT_AUTH=need
ARGUS_AUTH_URL=https://localhost:8081
BFF_AUTH_MTLS_ENABLED=true
BFF_AUTH_TLS_CA_FILE=$OUT/ca.crt
BFF_AUTH_TLS_CERT_FILE=$OUT/bff-auth-client.crt
BFF_AUTH_TLS_KEY_FILE=$OUT/bff-auth-client.key
BFF_AUTH_TLS_SERVER_NAME=localhost
BFF_REDIS_URL=rediss://localhost:6380
BFF_REDIS_USERNAME=default
BFF_REDIS_PASSWORD=argus-dev-redis-secret
BFF_REDIS_TLS_CA_FILE=$OUT/ca.crt
BFF_REDIS_TLS_CERT_FILE=$OUT/bff-redis-client.crt
BFF_REDIS_TLS_KEY_FILE=$OUT/bff-redis-client.key
BFF_REDIS_TLS_SERVER_NAME=localhost
EOF

chmod 600 "$OUT"/*.key "$OUT"/*.p12 "$OUT/.env.mtls"
echo "Generated a 14-day development mTLS PKI at $OUT"
echo "Run: set -a; source '$OUT/.env.mtls'; set +a"
