#!/usr/bin/env bash
#
# Sets the operator's total spend ceiling in USD, per CONTRACT.md's "Spend budget". When
# production spend reaches the ceiling, GET /iap/packages serves an empty catalog and every app's
# paywall goes empty — see that section for why the catalog, and not the offer, is the thing that
# has to be emptied.
#
# The ceiling counts spend by ordinary users only. Wallets registered with --internal are exempt,
# which is how development and QA installs stop eating the budget. Exemption is deliberately a
# server-side allowlist of wallet addresses rather than something the app asserts about itself: a
# build-type header would let anyone opt out of the ceiling by sending it.
#
# Dependencies: curl (jq is not required — the responses are flat).
#
# Usage:
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-budget.sh 200            # ceiling of $200
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-budget.sh --show         # current ceiling and spend
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-budget.sh --clear        # remove the ceiling
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-budget.sh --internal 0xabc,0xdef
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-budget.sh --uninternal 0xabc
#   AICOIN_PROXY_ADMIN_TOKEN=<token> ./set-budget.sh --list-internal
#
# Options:
#   <usd>              New ceiling, in dollars. 0 is the same as --clear.
#   --show             Print the current ceiling, production spend, internal spend and remaining.
#   --clear            Remove the ceiling entirely (sales are never stopped by budget again).
#   --internal <a,b>   Exempt these wallet addresses from the ceiling. Repeatable/comma-separated.
#   --uninternal <a,b> Stop exempting them.
#   --list-internal    Print the exempt list.
#   --url <base>       Defaults to http://localhost:8080, same as the other scripts here.
#
# The admin token must match the server's aicoin.adminToken, exactly as for set-coin-offer.sh.

set -euo pipefail

URL="http://localhost:8080"
ACTION=""
VALUE=""

die() { echo "error: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --url) URL="${2:-}"; shift 2 ;;
        --show) ACTION="show"; shift ;;
        --clear) ACTION="set"; VALUE="0"; shift ;;
        --list-internal) ACTION="list-internal"; shift ;;
        --internal) ACTION="internal-add"; VALUE="${2:-}"; shift 2 ;;
        --uninternal) ACTION="internal-remove"; VALUE="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) die "unknown option $1" ;;
        *) ACTION="set"; VALUE="$1"; shift ;;
    esac
done

[[ -n "$ACTION" ]] || die "nothing to do — pass a dollar amount, --show, --clear or --internal (see --help)"

if [[ "$ACTION" != "show" ]]; then
    [[ -n "${AICOIN_PROXY_ADMIN_TOKEN:-}" ]] || die "AICOIN_PROXY_ADMIN_TOKEN is not set"
fi

# Turns "a,b c" into the JSON array body the admin endpoint takes.
json_array() {
    local raw="${1//,/ }" out="" item
    for item in $raw; do
        [[ -z "$out" ]] && out="\"$item\"" || out="$out,\"$item\""
    done
    printf '[%s]' "$out"
}

case "$ACTION" in
    show)
        curl -fsS "$URL/budget"; echo
        ;;
    set)
        [[ "$VALUE" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "ceiling must be a non-negative number, got '$VALUE'"
        curl -fsS -X POST "$URL/admin/budget" \
            -H "X-Admin-Token: $AICOIN_PROXY_ADMIN_TOKEN" \
            -H 'Content-Type: application/json' \
            -d "{\"usd\":$VALUE}"; echo
        ;;
    internal-add)
        [[ -n "$VALUE" ]] || die "--internal needs at least one wallet address"
        curl -fsS -X POST "$URL/admin/internal-wallets" \
            -H "X-Admin-Token: $AICOIN_PROXY_ADMIN_TOKEN" \
            -H 'Content-Type: application/json' \
            -d "{\"add\":$(json_array "$VALUE")}"; echo
        ;;
    internal-remove)
        [[ -n "$VALUE" ]] || die "--uninternal needs at least one wallet address"
        curl -fsS -X POST "$URL/admin/internal-wallets" \
            -H "X-Admin-Token: $AICOIN_PROXY_ADMIN_TOKEN" \
            -H 'Content-Type: application/json' \
            -d "{\"remove\":$(json_array "$VALUE")}"; echo
        ;;
    list-internal)
        curl -fsS -X POST "$URL/admin/internal-wallets" \
            -H "X-Admin-Token: $AICOIN_PROXY_ADMIN_TOKEN" \
            -H 'Content-Type: application/json' \
            -d '{}'; echo
        ;;
esac
