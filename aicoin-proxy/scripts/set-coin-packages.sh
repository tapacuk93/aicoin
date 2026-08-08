#!/usr/bin/env bash
#
# Admin CLI for POST /admin/iap/packages, per CONTRACT.md's "Coin packages" section: reads a JSON
# file shaped exactly like GET /iap/packages' "packages" array
# (`[{"product_id":"...","coins":N,"usd_price_hint":N}, ...]`) and atomically overwrites
# aicoin:iap-packages with it. This is the one place "currently available coins" is set — every
# client app reads GET /iap/packages instead of hardcoding coin amounts.
#
# Usage:
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-packages.sh packages.json [proxy_base_url]
#
# proxy_base_url defaults to http://localhost:8080. The admin token must match the server's
# aicoin.adminToken (AICOIN_PROXY_ADMIN_TOKEN on the server) — this is the *client*-side token,
# read from the same-named env var here purely for convenience, not because the two processes
# share any state.

set -euo pipefail

PACKAGES_FILE="${1:-}"
BASE_URL="${2:-http://localhost:8080}"

if [[ -z "$PACKAGES_FILE" ]]; then
  echo "usage: AICOIN_PROXY_ADMIN_TOKEN=<token> $0 <packages.json> [proxy_base_url]" >&2
  exit 1
fi

if [[ ! -f "$PACKAGES_FILE" ]]; then
  echo "error: no such file: $PACKAGES_FILE" >&2
  exit 1
fi

if [[ -z "${AICOIN_PROXY_ADMIN_TOKEN:-}" ]]; then
  echo "error: AICOIN_PROXY_ADMIN_TOKEN is not set" >&2
  exit 1
fi

response=$(curl -sS -w '\n%{http_code}' \
  -X POST "${BASE_URL%/}/admin/iap/packages" \
  -H "X-Admin-Token: ${AICOIN_PROXY_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  --data-binary "@${PACKAGES_FILE}")

http_code=$(tail -n1 <<<"$response")
body=$(sed '$d' <<<"$response")

echo "$body"

if [[ "$http_code" != "200" ]]; then
  echo "error: server responded with HTTP $http_code" >&2
  exit 1
fi

echo "packages updated successfully." >&2
