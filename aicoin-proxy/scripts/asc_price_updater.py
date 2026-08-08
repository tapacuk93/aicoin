#!/usr/bin/env python3
"""Push a new manual price onto an App Store Connect in-app purchase.

Called by `adjust-iap-prices.sh --apply`. Kept in Python rather than bash because the flow needs
paginated price-point resolution and non-trivial JSON:API request bodies — both awkward and
error-prone in shell.

Auth is an ES256-signed App Store Connect JWT built from ASC_KEY_ID / ASC_ISSUER_ID /
ASC_PRIVATE_KEY_PATH, per
https://developer.apple.com/documentation/appstoreconnectapi/generating-tokens-for-api-requests

The request shapes below are not guesses — they are the exact shapes verified against the live API
when the twelve AICoin products were originally created (notably: the price-schedule `included`
entry's relationship is `inAppPurchasePricePoint`, *not* `pricePoint`, and the local id must use
the `${...}` form).

Dependencies: `pyjwt`, `cryptography`, `requests`.

Exit status is non-zero on any failure, so the calling script/cron surfaces problems loudly rather
than silently leaving a product mispriced.
"""
import argparse
import os
import sys
import time

try:
    import jwt
    import requests
except ImportError as exc:  # pragma: no cover - dependency guidance only
    sys.exit(f"missing dependency: {exc}. Install with: pip install pyjwt cryptography requests")

V1 = "https://api.appstoreconnect.apple.com/v1"
V2 = "https://api.appstoreconnect.apple.com/v2"


def build_token() -> str:
    key_id = os.environ.get("ASC_KEY_ID")
    issuer_id = os.environ.get("ASC_ISSUER_ID")
    key_path = os.environ.get("ASC_PRIVATE_KEY_PATH")
    if not (key_id and issuer_id and key_path):
        sys.exit("ASC_KEY_ID, ASC_ISSUER_ID and ASC_PRIVATE_KEY_PATH must all be set")
    with open(key_path) as handle:
        private_key = handle.read()
    now = int(time.time())
    # Apple rejects tokens whose exp is more than 20 minutes out.
    payload = {"iss": issuer_id, "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"}
    return jwt.encode(payload, private_key, algorithm="ES256", headers={"kid": key_id, "typ": "JWT"})


def headers(token: str) -> dict:
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def resolve_iap_id(token: str, app_id: str, product_id: str) -> str:
    """Map a productId string to its numeric App Store Connect in-app purchase resource id."""
    url = f"{V1}/apps/{app_id}/inAppPurchasesV2"
    params = {"filter[productId]": product_id, "limit": 200}
    while url:
        resp = requests.get(url, headers=headers(token), params=params, timeout=60)
        params = None
        resp.raise_for_status()
        body = resp.json()
        for item in body.get("data", []):
            if item["attributes"].get("productId") == product_id:
                return item["id"]
        url = body.get("links", {}).get("next")
    sys.exit(f"no in-app purchase found with productId {product_id} under app {app_id}")


def resolve_price_point(token: str, iap_id: str, customer_price: str, territory: str) -> str:
    """Find the inAppPurchasePricePoints id whose customerPrice equals `customer_price` exactly.

    Price points are per-in-app-purchase and per-territory, and the list is long enough to be
    paginated — hence the walk rather than a single filtered lookup (Apple exposes no
    customerPrice filter on this relationship).
    """
    url = f"{V2}/inAppPurchases/{iap_id}/pricePoints"
    params = {"filter[territory]": territory, "limit": 200}
    while url:
        resp = requests.get(url, headers=headers(token), params=params, timeout=60)
        params = None
        resp.raise_for_status()
        body = resp.json()
        for point in body.get("data", []):
            if point["attributes"].get("customerPrice") == customer_price:
                return point["id"]
        url = body.get("links", {}).get("next")
    sys.exit(f"no {territory} price point equal to {customer_price} for in-app purchase {iap_id}")


def current_price(token: str, iap_id: str, territory: str) -> str | None:
    """The in-app purchase's currently scheduled customerPrice, or None if it has no schedule yet.

    Used to skip no-op writes so a frequently-run cron doesn't churn Apple's API (and its rate
    limits) re-applying a price that is already in effect.
    """
    resp = requests.get(
        f"{V1}/inAppPurchasePriceSchedules/{iap_id}/manualPrices",
        headers=headers(token),
        params={"filter[territory]": territory, "include": "inAppPurchasePricePoint", "limit": 200},
        timeout=60,
    )
    if resp.status_code == 404:
        return None
    resp.raise_for_status()
    for included in resp.json().get("included", []):
        if included.get("type") == "inAppPurchasePricePoints":
            price = included.get("attributes", {}).get("customerPrice")
            if price:
                return price
    return None


def apply_price(token: str, iap_id: str, price_point_id: str, territory: str) -> None:
    """Replace the in-app purchase's manual price schedule so the new price takes effect now.

    `startDate: null` means "effective immediately"; posting a schedule supersedes the previous
    one for the given base territory.
    """
    body = {
        "data": {
            "type": "inAppPurchasePriceSchedules",
            "relationships": {
                "inAppPurchase": {"data": {"type": "inAppPurchases", "id": iap_id}},
                "baseTerritory": {"data": {"type": "territories", "id": territory}},
                "manualPrices": {"data": [{"type": "inAppPurchasePrices", "id": "${newPrice}"}]},
            },
        },
        "included": [
            {
                "type": "inAppPurchasePrices",
                "id": "${newPrice}",
                "attributes": {"startDate": None},
                "relationships": {
                    "inAppPurchaseV2": {"data": {"type": "inAppPurchases", "id": iap_id}},
                    # NOTE: `inAppPurchasePricePoint`, not `pricePoint` — the latter is rejected.
                    "inAppPurchasePricePoint": {
                        "data": {"type": "inAppPurchasePricePoints", "id": price_point_id}
                    },
                },
            }
        ],
    }
    resp = requests.post(f"{V1}/inAppPurchasePriceSchedules", headers=headers(token), json=body, timeout=60)
    if resp.status_code >= 300:
        sys.exit(f"price update failed ({resp.status_code}): {resp.text[:400]}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Set an App Store Connect in-app purchase's price.")
    parser.add_argument("--app-id", required=True, help="App Store Connect numeric app id")
    parser.add_argument("--product-id", required=True, help="IAP productId, e.g. com.example.aicoin.small")
    parser.add_argument("--price", required=True, help='Target customer price, e.g. "2.99"')
    parser.add_argument("--territory", default="USA", help="Base territory (default: USA)")
    parser.add_argument("--dry-run", action="store_true", help="Resolve and report, but do not write")
    args = parser.parse_args()

    token = build_token()
    iap_id = resolve_iap_id(token, args.app_id, args.product_id)
    existing = current_price(token, iap_id, args.territory)

    if existing == args.price:
        print(f"{args.product_id}: already at ${args.price} — no change")
        return

    price_point_id = resolve_price_point(token, iap_id, args.price, args.territory)
    if args.dry_run:
        print(f"{args.product_id}: DRY RUN would set ${existing} -> ${args.price} "
              f"(iap={iap_id}, pricePoint={price_point_id})")
        return

    apply_price(token, iap_id, price_point_id, args.territory)
    print(f"{args.product_id}: ${existing} -> ${args.price} (applied)")


if __name__ == "__main__":
    main()
