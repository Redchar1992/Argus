#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${ARGUS_DEV_PKI_DIR:-$ROOT/infra/tls/generated}"
NAME="prometheus-auth-client"

if [[ ! -f "$OUT/ca.crt" || ! -f "$OUT/ca.key" ]]; then
  echo "Base development PKI is missing; run ./infra/tls/generate-dev-pki.sh first." >&2
  exit 1
fi
if [[ -f "$OUT/$NAME.crt" && -f "$OUT/$NAME.key" ]]; then
  echo "Development Prometheus client identity is ready in $OUT"
  exit 0
fi
if [[ -e "$OUT/$NAME.crt" || -e "$OUT/$NAME.key" ]]; then
  echo "Refusing to overwrite a partial $NAME identity in $OUT." >&2
  exit 1
fi

umask 077
openssl genrsa -out "$OUT/$NAME.key" 3072 >/dev/null 2>&1
openssl req -new -sha256 -key "$OUT/$NAME.key" -out "$OUT/$NAME.csr" \
  -subj '/CN=prometheus/O=Argus Local Development' >/dev/null 2>&1
cat >"$OUT/$NAME.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=clientAuth
subjectAltName=DNS:prometheus
EOF
openssl x509 -req -sha256 -days 14 -in "$OUT/$NAME.csr" \
  -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" -CAcreateserial \
  -extfile "$OUT/$NAME.ext" -out "$OUT/$NAME.crt" >/dev/null 2>&1
rm -f "$OUT/$NAME.csr" "$OUT/$NAME.ext"
chmod 600 "$OUT/$NAME.key"
echo "Development Prometheus client identity is ready in $OUT"
