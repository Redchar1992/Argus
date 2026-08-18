#!/usr/bin/env bash
set -euo pipefail

OUTPUT=${1:-.env.jwt.generated}
if [[ -e "$OUTPUT" ]]; then
  echo "Refusing to overwrite existing $OUTPUT" >&2
  exit 1
fi

umask 077
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

b64_file() {
  openssl base64 -A -in "$1"
}

generate_ring() {
  local purpose=$1
  local var_prefix=$2
  local kid="${purpose}-$(date -u +%Y%m%d)-$(openssl rand -hex 3)"
  local pem="$TMP_DIR/${purpose}.pem"
  local private_der="$TMP_DIR/${purpose}.private.der"
  local public_der="$TMP_DIR/${purpose}.public.der"

  openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$pem"
  openssl pkcs8 -topk8 -nocrypt -in "$pem" -outform DER -out "$private_der"
  openssl pkey -in "$pem" -pubout -outform DER -out "$public_der" 2>/dev/null

  printf '%s_JWT_PRIMARY_KEY_ID=%s\n' "$var_prefix" "$kid" >> "$OUTPUT"
  printf '%s_JWT_PRIVATE_KEYS=%s:%s\n' "$var_prefix" "$kid" "$(b64_file "$private_der")" >> "$OUTPUT"
  printf '%s_JWT_PUBLIC_KEYS=%s:%s\n' "$var_prefix" "$kid" "$(b64_file "$public_der")" >> "$OUTPUT"
}

generate_ring auth ARGUS_AUTH
generate_ring workload ARGUS_WORKLOAD
chmod 600 "$OUTPUT"
echo "Wrote two independent 3072-bit RSA key rings to $OUTPUT (mode 0600)."
echo "Keep *_PRIVATE_KEYS only in the owning signer secret: auth-service or orchestrator."
