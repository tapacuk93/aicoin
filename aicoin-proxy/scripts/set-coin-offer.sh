#!/usr/bin/env bash
#
# Sets how many aicoin every app is selling right now — the one number, per CONTRACT.md's "The
# current offer" section. Every app reads it from GET /iap/offer, displays exactly that amount,
# re-checks it immediately before charging, and buys whichever of its four fixed-price products
# covers it. Coin amounts are decoupled from products entirely: the products are four price
# points, this number is what a purchase at one of them credits.
#
# Dependencies: curl. (jq only if present — output is pretty-printed when it is.)
#
# Usage:
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-offer.sh 350
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-offer.sh 350 --price 9.99
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-offer.sh --close
#   ./set-coin-offer.sh --show                       # read-only, no token needed
#
# Options:
#   <coins>          How many aicoin one purchase grants. The server prices it from the live
#                    /price signal and picks the cheapest product whose fixed price covers it,
#                    rounding *up* — it never sells coins below what they're computed to be worth.
#   --price <usd>    Skip the computed price and put the offer on the named price point instead.
#                    Required when the price signal is too thin to trust (a fresh deploy reports
#                    price_usd 0, which would otherwise price any amount at the cheapest point);
#                    the server refuses to guess in that case and tells you so.
#   --close          Stop selling: GET /iap/offer starts reporting no offer and paywalls show
#                    their empty state. Purchases already in flight are unaffected — a pin issued
#                    before the close is still honoured, so nobody mid-checkout is shortchanged.
#   --show           Print the current offer and exit. Read-only; needs no admin token.
#   --url <base>     Proxy base URL, default http://localhost:8080 (same as the other scripts).
#
# The admin token must match the server's aicoin.adminToken, exactly as for set-coin-packages.sh.

set -euo pipefail

BASE_URL="http://localhost:8080"
COINS=""
PRICE=""
ACTION="set"

usage() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

# Pretty-prints a JSON body when jq is around, passes it through untouched when it isn't — the
# script's one hard dependency stays curl, so it runs on a bare production host.
emit() {
  if command -v jq >/dev/null 2>&1; then
    jq '.' <<<"$1" 2>/dev/null || echo "$1"
  else
    echo "$1"
  fi
}

if ! command -v curl >/dev/null 2>&1; then
  echo "error: required dependency 'curl' not found on PATH" >&2
  exit 1
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --price)
      [[ $# -ge 2 ]] || { echo "error: --price needs a USD amount" >&2; exit 1; }
      if [[ ! "$2" =~ ^[0-9]+(\.[0-9]{1,2})?$ ]]; then
        echo "error: --price expects a USD amount like 9.99, got: $2" >&2
        exit 1
      fi
      PRICE="$2"
      shift 2
      ;;
    --close)
      ACTION="close"
      shift
      ;;
    --show)
      ACTION="show"
      shift
      ;;
    --url)
      [[ $# -ge 2 ]] || { echo "error: --url needs a proxy base url" >&2; exit 1; }
      BASE_URL="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    -*)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      if [[ -n "$COINS" ]]; then
        echo "error: only one coin amount may be given, got '$COINS' and '$1'" >&2
        exit 1
      fi
      if [[ ! "$1" =~ ^[0-9]+$ ]] || [[ "$1" -le 0 ]]; then
        echo "error: coin amount must be a positive integer, got: $1" >&2
        exit 1
      fi
      COINS="$1"
      shift
      ;;
  esac
done

if [[ "$ACTION" == "show" ]]; then
  emit "$(curl -sS "${BASE_URL%/}/iap/offer")"
  exit 0
fi

if [[ "$ACTION" == "close" ]]; then
  if [[ -n "$COINS" ]]; then
    echo "error: --close takes no coin amount (it stops sales entirely)" >&2
    exit 1
  fi
  # coins:0 is the contract's "close sales" encoding — see CoinOfferHandler.serveAdminSet.
  BODY='{"coins":0}'
elif [[ -z "$COINS" ]]; then
  echo "error: no coin amount given" >&2
  usage >&2
  exit 1
elif [[ -n "$PRICE" ]]; then
  BODY="{\"coins\":${COINS},\"usd_price\":${PRICE}}"
else
  BODY="{\"coins\":${COINS}}"
fi

if [[ -z "${AICOIN_PROXY_ADMIN_TOKEN:-}" ]]; then
  echo "error: AICOIN_PROXY_ADMIN_TOKEN is not set" >&2
  exit 1
fi

response=$(curl -sS -w '\n%{http_code}' \
  -X POST "${BASE_URL%/}/admin/iap/offer" \
  -H "X-Admin-Token: ${AICOIN_PROXY_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  --data-binary "$BODY")

http_code=$(tail -n1 <<<"$response")
body=$(sed '$d' <<<"$response")

emit "$body"

if [[ "$http_code" != "200" ]]; then
  # 409 is the server refusing to guess: either the price signal is too thin, or no price point
  # covers this many coins. Both are fixed by naming a price point with --price (or asking for
  # fewer coins), so say that rather than leaving a bare status code.
  if [[ "$http_code" == "409" ]]; then
    echo "hint: pass --price <usd> to put the offer on a specific price point, or lower the amount." >&2
  fi
  echo "error: server responded with HTTP $http_code" >&2
  exit 1
fi

if [[ "$ACTION" == "close" ]]; then
  echo "sales closed — no offer is live." >&2
else
  echo "offer updated: ${COINS} aicoin." >&2
fi
