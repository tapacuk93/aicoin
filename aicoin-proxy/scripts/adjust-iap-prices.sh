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
# /iap/packages array), awk (the price arithmetic/rounding). Applying prices additionally needs
# python3 with pyjwt/cryptography/requests, for asc_price_updater.py (see --apply below).
#
# Usage:
#   ./adjust-iap-prices.sh [proxy_base_url]            # report only (default — safe, read-only)
#   ./adjust-iap-prices.sh [proxy_base_url] --apply    # actually push new prices to App Store Connect
#
# Reporting is the default deliberately: this runs unattended on cron, and silently repricing live
# products is not something that should happen unless explicitly asked for.
#
# Env vars (required only with --apply):
#   ASC_KEY_ID, ASC_ISSUER_ID, ASC_PRIVATE_KEY_PATH

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
APPLY="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# App Store Connect numeric app ids, keyed by the product-id prefix each app's packages use.
# Note learnit (no hyphen) vs the real bundle id com.tarasmaslov.learn-it — Apple forbids hyphens
# in productId, so the two genuinely differ for that one app. See CONTRACT.md's IAP section.
asc_app_id_for_product() {
  case "$1" in
    com.tarasmaslov.infiniteairadio.*)     echo "6788594796" ;;
    com.tarasmaslov.alllanguageslearner.*) echo "6796105967" ;;
    com.tarasmaslov.learnit.*)             echo "6797492573" ;;
    *)                                     echo "" ;;
  esac
}

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
# update_asc_price: pushes a new manual price onto a live App Store Connect in-app purchase.
#
# The actual API work lives in asc_price_updater.py alongside this script: resolving a productId
# to its numeric IAP resource id, walking the paginated per-territory price-point list to find the
# id matching the target price, and POSTing the price schedule. That flow needs pagination and
# nested JSON:API bodies, which are error-prone in shell — and its request shapes are the ones
# verified against the live API when the twelve AICoin products were created, not guesses.
#
# It skips writes when the product is already at the target price, so running this hourly on cron
# does not churn Apple's rate limits.
# ---------------------------------------------------------------------------------------------
update_asc_price() {
  local product_id="$1" target="$2"
  local app_id
  app_id=$(asc_app_id_for_product "$product_id")
  if [[ -z "$app_id" ]]; then
    echo "error: no App Store Connect app id mapped for product $product_id" >&2
    return 1
  fi
  if [[ -z "${ASC_KEY_ID:-}" || -z "${ASC_ISSUER_ID:-}" || -z "${ASC_PRIVATE_KEY_PATH:-}" ]]; then
    echo "error: ASC_KEY_ID/ASC_ISSUER_ID/ASC_PRIVATE_KEY_PATH must all be set to use --apply" >&2
    return 1
  fi
  python3 "$SCRIPT_DIR/asc_price_updater.py" \
    --app-id "$app_id" --product-id "$product_id" --price "$target"
}

# ---------------------------------------------------------------------------------------------
# Main: fetch the live price signal + current packages, log the recomputed target price for each.
# ---------------------------------------------------------------------------------------------
price_json=$(curl -sS "${BASE_URL%/}/price")
price_usd=$(jq -r '.price_usd' <<<"$price_json")
weighted_total=$(jq -r '.weighted_total // 0' <<<"$price_json")
if [[ -z "$price_usd" || "$price_usd" == "null" ]]; then
  echo "error: could not read price_usd from ${BASE_URL%/}/price" >&2
  exit 1
fi

echo "current price_usd=$price_usd (weighted_total=$weighted_total)"

# ---------------------------------------------------------------------------------------------
# SAFETY GUARD 1 — refuse to price off an empty or near-empty signal.
#
# price_usd is a recency-weighted average over recorded *paid calls*. On a fresh deploy (or after
# a ledger wipe) there are no events, so it reports 0.0 — and the naive formula then collapses
# EVERY package to the cheapest tier: a 5000-coin pack would be repriced from $44.99 to $0.99,
# selling 5000 real AI calls for under a dollar. A thin sample is nearly as bad, since one
# unusually cheap call would drag the whole ladder down. So: never auto-apply below a meaningful
# sample size. Reporting still runs, so the cron log shows what it *would* do once data exists.
# ---------------------------------------------------------------------------------------------
MIN_WEIGHTED_EVENTS="${MIN_WEIGHTED_EVENTS:-50}"
signal_usable=1
if awk -v p="$price_usd" 'BEGIN { exit !(p <= 0) }'; then
  echo "WARNING: price_usd is $price_usd — no usage recorded yet; refusing to apply any price change." >&2
  signal_usable=0
elif awk -v w="$weighted_total" -v m="$MIN_WEIGHTED_EVENTS" 'BEGIN { exit !(w < m) }'; then
  echo "WARNING: only $weighted_total weighted events recorded (need >= $MIN_WEIGHTED_EVENTS); refusing to apply any price change." >&2
  signal_usable=0
fi
if [[ "$APPLY" == "--apply" && "$signal_usable" -eq 0 ]]; then
  echo "--apply requested but the price signal is not yet trustworthy — reporting only." >&2
  APPLY=""
fi

# ---------------------------------------------------------------------------------------------
# SAFETY GUARD 2 — circuit-breaker on any single large repricing.
#
# Even with a healthy signal, a bug upstream (or a genuinely wild swing in provider costs) should
# not silently move a live product's price by an order of magnitude. Anything beyond this factor
# in either direction is reported and skipped, so a human decides.
# ---------------------------------------------------------------------------------------------
MAX_CHANGE_FACTOR="${MAX_CHANGE_FACTOR:-2.0}"
change_is_sane() {
  local current="$1" target="$2"
  awk -v c="$current" -v t="$target" -v f="$MAX_CHANGE_FACTOR" \
    'BEGIN { if (c <= 0) { exit 1 } ; r = t / c ; exit !(r <= f && r >= 1 / f) }'
}

curl -sS "${BASE_URL%/}/iap/packages" | jq -c '.packages[]' | while IFS= read -r pkg; do
  product_id=$(jq -r '.product_id' <<<"$pkg")
  coins=$(jq -r '.coins' <<<"$pkg")
  current_price_hint=$(jq -r '.usd_price_hint' <<<"$pkg")

  target=$(target_price "$coins" "$price_usd")

  if awk -v a="$target" -v b="$current_price_hint" 'BEGIN { exit !(a == b) }'; then
    echo "keep $product_id at \$$current_price_hint (coins=$coins, already at the current target)"
  elif ! change_is_sane "$current_price_hint" "$target"; then
    echo "SKIP $product_id: \$$current_price_hint -> \$$target exceeds the ${MAX_CHANGE_FACTOR}x circuit breaker (coins=$coins, price_usd=$price_usd) — review manually" >&2
  elif [[ "$APPLY" == "--apply" ]]; then
    echo "updating $product_id: \$$current_price_hint -> \$$target (coins=$coins, price_usd=$price_usd)"
    update_asc_price "$product_id" "$target"
  else
    echo "would update $product_id: \$$current_price_hint -> \$$target (coins=$coins, price_usd=$price_usd)  [re-run with --apply to push]"
  fi
done
