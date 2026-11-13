#!/usr/bin/env bash
# Development tokens, for running the services locally and for the smoke test. NOT for production,
# where an identity provider issues the tokens and the services trust its issuer instead.
#
#   scripts/dev-jwt.sh keys                                          create the key pair (once)
#   scripts/dev-jwt.sh token '"scope":"promotions:read promotions:write"'   a head-office token
#   scripts/dev-jwt.sh token '"store_id":"store-1001"'                       a store employee's token
#
# The key pair lives in .dev-keys/ (git-ignored). The services only ever get the public key;
# the private key stays on this machine and signs the tokens.
set -euo pipefail

KEYS="$(cd "$(dirname "$0")/.." && pwd)/.dev-keys"
PRIVATE="$KEYS/jwt-private.pem"
PUBLIC="$KEYS/jwt-public.pem"

ensure_keys() {
  [ -f "$PRIVATE" ] && [ -f "$PUBLIC" ] && return
  mkdir -p "$KEYS"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$PRIVATE" 2> /dev/null
  chmod 600 "$PRIVATE"
  openssl pkey -in "$PRIVATE" -pubout -out "$PUBLIC"
  chmod 644 "$PUBLIC" # readable by the services' non-root user inside the containers
  echo "created $PUBLIC" >&2
}

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

token() {
  local claims=$1 now
  now=$(date +%s)
  local header payload signature
  header=$(printf '{"alg":"RS256","typ":"JWT"}' | base64url)
  payload=$(printf '{"iss":"store-ops-dev","sub":"dev-user","iat":%d,"exp":%d,%s}' "$now" $((now + 3600)) "$claims" | base64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$PRIVATE" -binary | base64url)
  printf '%s.%s.%s\n' "$header" "$payload" "$signature"
}

case "${1:-}" in
  keys) ensure_keys ;;
  token)
    [ $# -eq 2 ] || { echo "usage: $0 token '<extra JSON claims>'" >&2; exit 2; }
    ensure_keys
    token "$2"
    ;;
  *) echo "usage: $0 keys | token '<extra JSON claims>'" >&2; exit 2 ;;
esac
