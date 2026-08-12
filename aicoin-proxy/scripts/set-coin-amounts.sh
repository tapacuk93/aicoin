#!/usr/bin/env bash
#
# Sets how many aicoin each package currently sells, across every app, per CONTRACT.md's "Coin
# packages" section. This is a convenience layer over set-coin-packages.sh: instead of authoring
# all twelve entries (4 tiers x 3 apps) by hand, it reads the packages that are live right now
# from GET /iap/packages, rewrites only the coin amounts you name, and hands the result to
# set-coin-packages.sh — so there is still exactly one write path (POST /admin/iap/packages) and
# one source of truth (the aicoin:iap-packages Redis key every client app reads).
#
# Coin amounts only. USD price hints and the product-id list are carried through untouched — see
# adjust-iap-prices.sh for prices, which is the half that moves automatically.
#
# Dependencies: curl, jq (same as adjust-iap-prices.sh — the packages array is nested JSON).
#
# Usage:
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-amounts.sh --small 100 --xl 6000
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-amounts.sh --tier medium=250 --dry-run
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-coin-amounts.sh --large 1200 \
#       --app com.tarasmaslov.learnit --url https://proxy.aicoin.oeaio.com
#
# Options:
#   --small N | --medium N | --large N | --xl N
#         New coin amount for that tier, applied to every app's product for it.
#   --tier <suffix>=N
#         Same thing for any tier suffix, including ones not named above (product ids are
#         <app-prefix>.aicoin.<suffix>). Repeatable.
#   --app <product-id-prefix>
#         Only touch this app's products; repeatable. Default: every app. Note the prefix is the
#         product-id prefix, which for Learn It is com.tarasmaslov.learnit (Apple forbids the
#         hyphen in its real bundle id com.tarasmaslov.learn-it).
#   --dry-run
#         Print the before/after table and exit without writing anything.
#   --url <proxy_base_url>
#         Defaults to http://localhost:8080, same as the other scripts here.
#
# The admin token must match the server's aicoin.adminToken, exactly as for set-coin-packages.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="http://localhost:8080"
DRY_RUN=0
OVERRIDES="{}"
APPS="[]"

# Prints the header comment block above as the help text, so the two can't drift apart.
usage() {
  awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

add_override() {
  local tier="$1" coins="$2"
  if [[ ! "$coins" =~ ^[0-9]+$ ]] || [[ "$coins" -le 0 ]]; then
    echo "error: coin amount for '$tier' must be a positive integer, got: $coins" >&2
    exit 1
  fi
  # Strips any leading zeroes too, so the amount lands in the JSON as a bare number.
  OVERRIDES=$(jq -c --arg t "$tier" --argjson c "$((10#$coins))" '. + {($t): $c}' <<<"$OVERRIDES")
}

for dep in curl jq; do
  if ! command -v "$dep" >/dev/null 2>&1; then
    echo "error: required dependency '$dep' not found on PATH" >&2
    exit 1
  fi
done

while [[ $# -gt 0 ]]; do
  case "$1" in
    --small|--medium|--large|--xl)
      [[ $# -ge 2 ]] || { echo "error: $1 needs a coin amount" >&2; exit 1; }
      add_override "${1#--}" "$2"
      shift 2
      ;;
    --tier)
      [[ $# -ge 2 ]] || { echo "error: --tier needs <suffix>=<coins>" >&2; exit 1; }
      if [[ ! "$2" =~ ^([A-Za-z0-9_]+)=([0-9]+)$ ]]; then
        echo "error: --tier expects <suffix>=<coins>, got: $2" >&2
        exit 1
      fi
      add_override "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
      shift 2
      ;;
    --app)
      [[ $# -ge 2 ]] || { echo "error: --app needs a product-id prefix" >&2; exit 1; }
      APPS=$(jq -c --arg a "$2" '. + [$a]' <<<"$APPS")
      shift 2
      ;;
    --url)
      [[ $# -ge 2 ]] || { echo "error: --url needs a proxy base url" >&2; exit 1; }
      BASE_URL="$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if [[ "$OVERRIDES" == "{}" ]]; then
  echo "error: nothing to set — pass at least one of --small/--medium/--large/--xl/--tier" >&2
  usage >&2
  exit 1
fi

if [[ "$DRY_RUN" -eq 0 && -z "${AICOIN_PROXY_ADMIN_TOKEN:-}" ]]; then
  # Checked up front rather than after the fetch, so a missing token fails before any work.
  echo "error: AICOIN_PROXY_ADMIN_TOKEN is not set" >&2
  exit 1
fi

current=$(curl -sS "${BASE_URL%/}/iap/packages")
if ! jq -e '.packages | type == "array" and length > 0' >/dev/null 2>&1 <<<"$current"; then
  echo "error: could not read a packages array from ${BASE_URL%/}/iap/packages" >&2
  echo "$current" >&2
  exit 1
fi

# Rewrite coins in place: match each product id as <app-prefix>.aicoin.<tier>, and swap the amount
# only when that tier was named and the app passes the (optional) --app filter. Anything else —
# including a product id that doesn't fit the pattern — is carried through byte-for-byte.
updated=$(jq -c --argjson ov "$OVERRIDES" --argjson apps "$APPS" '
  [ .packages[]
    | . as $p
    | (($p.product_id // "") | capture("^(?<app>.+)\\.aicoin\\.(?<tier>[^.]+)$") // null) as $m
    | if $m != null
         and (($apps | length) == 0 or ($apps | index($m.app)) != null)
         and ($ov | has($m.tier))
      then $p + {coins: $ov[$m.tier]}
      else $p
      end ]' <<<"$current")

# Before/after table, printed for a dry run and a real write alike — this changes what every app
# is selling, so the operator should always see the full list, not just the rows that moved.
jq -r --argjson before "$(jq -c '.packages' <<<"$current")" '
  to_entries[]
  | .value as $new
  | ($before[.key]) as $old
  | if $old.coins == $new.coins
    then "  keep   \($new.product_id): \($new.coins) coins"
    else "  CHANGE \($new.product_id): \($old.coins) -> \($new.coins) coins"
    end' <<<"$updated"

changed=$(jq -r --argjson before "$(jq -c '.packages' <<<"$current")" \
  '[to_entries[] | select(.value.coins != $before[.key].coins)] | length' <<<"$updated")

if [[ "$changed" -eq 0 ]]; then
  echo "no coin amounts changed — nothing to write." >&2
  exit 0
fi

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "dry run: $changed package(s) would change; re-run without --dry-run to apply." >&2
  exit 0
fi

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
jq '.' <<<"$updated" >"$tmp"

# The actual write goes through set-coin-packages.sh so POST /admin/iap/packages stays the single
# write path, and its server-side validation (non-empty product_id, positive integer coins) is the
# authority on what's acceptable.
"$SCRIPT_DIR/set-coin-packages.sh" "$tmp" "$BASE_URL"
