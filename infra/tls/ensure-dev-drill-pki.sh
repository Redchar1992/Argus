#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ARGUS_DEV_PKI_DIR:-$ROOT/infra/tls/generated}"

if [[ ! -f "$OUT/ca.crt" || ! -f "$OUT/ca.key" ]]; then
  echo "Base development PKI is missing; run ./infra/tls/generate-dev-pki.sh first." >&2
  exit 1
fi

issue_if_missing() {
  local name="$1" san="$2"
  if [[ -f "$OUT/$name.crt" && -f "$OUT/$name.key" ]]; then return; fi
  if [[ -e "$OUT/$name.crt" || -e "$OUT/$name.key" ]]; then
    echo "Refusing to overwrite a partial $name identity in $OUT." >&2
    exit 1
  fi
  umask 077
  openssl genrsa -out "$OUT/$name.key" 3072 >/dev/null 2>&1
  openssl req -new -sha256 -key "$OUT/$name.key" -out "$OUT/$name.csr" \
    -subj "/CN=$name/O=Argus Local Development" >/dev/null 2>&1
  cat >"$OUT/$name.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=$san
EOF
  openssl x509 -req -sha256 -days 14 -in "$OUT/$name.csr" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
    -extfile "$OUT/$name.ext" -out "$OUT/$name.crt" >/dev/null 2>&1
  rm -f "$OUT/$name.csr" "$OUT/$name.ext"
  chmod 600 "$OUT/$name.key"
}

issue_if_missing redis-drill-primary 'DNS:localhost,DNS:redis-drill-primary,IP:127.0.0.1'
issue_if_missing redis-drill-replica 'DNS:localhost,DNS:redis-drill-replica,IP:127.0.0.1'
echo "Development Redis drill identities are ready in $OUT"
