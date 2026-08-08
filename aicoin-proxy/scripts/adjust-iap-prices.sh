#!/usr/bin/env bash
#
# Hourly cron job on the Lightsail production host, per CONTRACT.md's "Automatic price
# adjustment" section: re-derives each IAP coin package's target USD price from the *current*
# GET /price signal (not the launch estimate) and logs what it *would* set each product's App
# Store price to. Coin amounts never change here, only price — see set-coin-packages.sh for
# changing coin amounts.
#
# Formula (mirrors AppStorePriceRounding.java's tested reference implementation exactly — keep
# the two in sync if either changes): target = coins * price_usd * (1+feeMargin) / (1-appleCut),
# rounded to the nearest entry in the same PRICE_TIERS ladder as the Java test suite covers.
#
# Dependencies: curl, jq (JSON parsing — pure bash/awk can't safely walk the nested
# /iap/packages array), awk (the price arithmetic/rounding), openssl + python3 (only for
# build_asc_jwt's DER->raw ECDSA signature conversion — see that function's comment).
#
# Usage:
#   ./adjust-iap-prices.sh [proxy_base_url]
# Env vars (only required if/when update_asc_price is actually wired in — see its comment):
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_PRIVATE_KEY_PATH

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"

APPLE_CUT="0.30"
FEE_MARGIN="0.50"
# Must exactly match AppStorePriceRounding.PRICE_TIERS in the Java test suite.
PRICE_TIERS="0.99 1.99 2.99 3.99 4.99 5.99 6.99 7.99 8.99 9.99 11.99 13.99 15.99 17.99 19.99 24.99 29.99 34.99 39.99 44.99 49.99 59.99 69.99 79.99 89.99 99.99 119.99 149.99 199.99"

for dep in curl jq awk; do
  if ! command -v "$dep" >/dev/null 2>&1; then
    echo "error: required dependency '$dep' not found on PATH" >&2
    exit 1
  fi
done

round_to_nearest_tier() {
  local raw="$1"
  awk -v raw="$raw" -v tiers="$PRICE_TIERS" '
    BEGIN {
      n = split(tiers, arr, " ")
      best = arr[1]
      bestDist = (raw > best) ? raw - best : best - raw
      for (i = 2; i <= n; i++) {
        d = (raw > arr[i]) ? raw - arr[i] : arr[i] - raw
        if (d < bestDist) { bestDist = d; best = arr[i] }
      }
      printf "%.2f", best
    }'
}

target_price() {
  local coins="$1" price_usd="$2"
  local raw
  raw=$(awk -v coins="$coins" -v price="$price_usd" -v margin="$FEE_MARGIN" -v cut="$APPLE_CUT" \
    'BEGIN { printf "%.6f", coins * price * (1 + margin) / (1 - cut) }')
  round_to_nearest_tier "$raw"
}

# ---------------------------------------------------------------------------------------------
# update_asc_price: builds a real ES256-signed App Store Connect API bearer JWT and is meant to
# PATCH/POST the new manual price point onto an existing in-app purchase's
# inAppPurchasePriceSchedule. The JWT construction below is real and complete (Apple's auth spec
# is stable and well-documented: https://developer.apple.com/documentation/appstoreconnectapi/generating-tokens-for-api-requests).
# The actual API call is INTENTIONALLY LEFT AS A STUB — see the TODO inside — because:
#
#   1. App Store Connect API v2 in-app purchases require resolving a *price point id* per
#      territory before you can reference it in a price schedule (GET
#      /v1/inAppPurchases/{id}/pricePoints, filtered/matched by territory + customerPrice) — this
#      proxy doesn't yet know the mapping from "$X.99" to the specific inAppPurchasePricePoints
#      resource id Apple expects, which varies by territory and by the in-app purchase's own id.
#   2. The exact current request body shape for creating/updating an
#      inAppPurchasePriceSchedule (manualPrices relationships, startDate handling, base
#      territory selection) is the part of Apple's API most likely to have shifted since this
#      script was written, and getting it wrong risks silently mispricing a live product — this
#      fails loudly (a clear log line) instead of silently doing nothing or guessing a shape.
#
# Wiring in the real call requires: (a) one extra authenticated GET to resolve the price point id
# for the target price + territory, then (b) a verified-against-current-docs POST/PATCH to
# /v1/inAppPurchasePriceSchedules referencing that price point id.
# ---------------------------------------------------------------------------------------------
build_asc_jwt() {
  local key_id="$1" issuer_id="$2" private_key_path="$3"
  for dep in openssl python3; do
    if ! command -v "$dep" >/dev/null 2>&1; then
      echo "error: required dependency '$dep' not found on PATH (needed by build_asc_jwt)" >&2
      return 1
    fi
  done

  local now exp header payload signing_input der_sig
  now=$(date +%s)
  exp=$((now + 1200)) # Apple rejects tokens with exp more than 20 minutes out.

  header=$(printf '{"alg":"ES256","kid":"%s","typ":"JWT"}' "$key_id" | base64 | tr -d '=' | tr '/+' '_-' | tr -d '\n')
  payload=$(printf '{"iss":"%s","iat":%d,"exp":%d,"aud":"appstoreconnect-v1"}' "$issuer_id" "$now" "$exp" \
    | base64 | tr -d '=' | tr '/+' '_-' | tr -d '\n')
  signing_input="${header}.${payload}"

  # openssl's ECDSA signature output is ASN.1 DER (SEQUENCE{INTEGER r, INTEGER s}); JWS ES256
  # requires raw big-endian r||s (32+32 bytes) instead — convert with a small, self-contained
  # python3 snippet (no external Python packages) rather than hand-rolling ASN.1 parsing in awk.
  der_sig=$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign "$private_key_path" | base64)

  local jose_sig
  jose_sig=$(python3 - "$der_sig" <<'PYEOF'
import base64, sys
der = base64.b64decode(sys.argv[1])
# Minimal DER SEQUENCE{INTEGER, INTEGER} reader — exactly the shape openssl produces here.
assert der[0] == 0x30
idx = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7f)
def read_int(data, i):
    assert data[i] == 0x02
    length = data[i + 1]
    start = i + 2
    value = data[start:start + length]
    value = value.lstrip(b'\x00') or b'\x00'
    return value, start + length
r, idx = read_int(der, idx)
s, idx = read_int(der, idx)
r = r.rjust(32, b'\x00')
s = s.rjust(32, b'\x00')
raw = r + s
print(base64.urlsafe_b64encode(raw).rstrip(b'=').decode())
PYEOF
)

  printf '%s.%s' "$signing_input" "$jose_sig"
}

update_asc_price() {
  local product_id="$1" price_tier_id="$2"
  if [[ -z "${ASC_KEY_ID:-}" || -z "${ASC_ISSUER_ID:-}" || -z "${ASC_PRIVATE_KEY_PATH:-}" ]]; then
    echo "error: ASC_KEY_ID/ASC_ISSUER_ID/ASC_PRIVATE_KEY_PATH must all be set to call the App Store Connect API" >&2
    return 1
  fi
  local jwt
  jwt=$(build_asc_jwt "$ASC_KEY_ID" "$ASC_ISSUER_ID" "$ASC_PRIVATE_KEY_PATH")

  # TODO(see comment above build_asc_jwt): the real App Store Connect API call belongs here —
  # e.g. resolve a price point id for $price_tier_id/territory via
  # GET https://api.appstoreconnect.apple.com/v1/inAppPurchases/{id}/pricePoints, then
  # POST/PATCH https://api.appstoreconnect.apple.com/v1/inAppPurchasePriceSchedules with that
  # relationship, both bearer-authenticated with "$jwt". Not implemented — see file header.
  echo "STUB: would update App Store Connect price for $product_id to price point $price_tier_id (JWT built, API call not wired — see build_asc_jwt/update_asc_price comments in this script)"
}

# ---------------------------------------------------------------------------------------------
# Main: fetch the live price signal + current packages, log the recomputed target price for each.
# ---------------------------------------------------------------------------------------------
price_usd=$(curl -sS "${BASE_URL%/}/price" | jq -r '.price_usd')
if [[ -z "$price_usd" || "$price_usd" == "null" ]]; then
  echo "error: could not read price_usd from ${BASE_URL%/}/price" >&2
  exit 1
fi

echo "current price_usd=$price_usd"

curl -sS "${BASE_URL%/}/iap/packages" | jq -c '.packages[]' | while IFS= read -r pkg; do
  product_id=$(jq -r '.product_id' <<<"$pkg")
  coins=$(jq -r '.coins' <<<"$pkg")
  current_price_hint=$(jq -r '.usd_price_hint' <<<"$pkg")

  target=$(target_price "$coins" "$price_usd")

  if awk -v a="$target" -v b="$current_price_hint" 'BEGIN { exit !(a == b) }'; then
    echo "would keep $product_id at \$$current_price_hint (coins=$coins, already at the current target)"
  else
    echo "would update $product_id: \$$current_price_hint -> \$$target (coins=$coins, price_usd=$price_usd)"
    # update_asc_price "$product_id" "$target"   # see update_asc_price's comment — not auto-invoked.
  fi
done
