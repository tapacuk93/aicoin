package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure logic for the <b>current offer</b> — the single coin amount every app is selling right
 * now, per CONTRACT.md's "The current offer" section. No I/O, so it unit-tests without Redis,
 * same style as {@link PriceCalculator} and {@link IapPackages}.
 *
 * <p>The offer model inverts the old per-tier packages model. Previously each of the four Apple
 * products carried its own coin amount and its App Store <em>price</em> floated (see {@code
 * scripts/adjust-iap-prices.sh}). Now the operator sets one <em>coin amount</em>, and the four
 * products are nothing but four <b>fixed</b> price points — the offer picks whichever product's
 * price covers what those coins are worth, and that product's purchase credits the offer's coin
 * amount, not the product's own. Consequently the four price points must stay genuinely fixed in
 * App Store Connect: if the repricer moved {@code .large} to $6.99 while the catalog here still
 * says $9.99, every offer resolving to {@code .large} would undercharge by the difference. That
 * is why {@code adjust-iap-prices.sh} refuses to apply prices under this model.
 *
 * <p><b>Rounding direction.</b> The target price rounds <b>up</b> to the cheapest price point
 * that covers it — deliberately unlike {@link AppStorePriceRounding#roundToNearestTier}, which
 * rounds to the nearest and exists for the per-product repricer this path replaces. Nearest-tier
 * would happily sell 350 coins worth $6.45 at the $2.99 point. Rounding up can only ever
 * overcharge relative to the raw target, which is the safe direction for the operator; the excess
 * is margin on top of the configured {@link AppStorePriceRounding#FEE_MARGIN}, not a loss.
 */
final class CoinOffer {

    /** Separates an app's product-id prefix from the tier suffix: {@code <prefix>.aicoin.<tier>}. */
    private static final String TIER_INFIX = ".aicoin.";

    /** Price points are compared in whole cents — {@code 9.99} read back out of JSON is not exactly 9.99. */
    private static final double PRICE_EPSILON = 0.005;

    private CoinOffer() {
    }

    /**
     * One price point: the tier suffix ({@code small}/{@code medium}/...), the fixed USD price
     * every app charges at it, and the concrete per-app product ids that sit at that price.
     */
    static final class Tier {
        private final String suffix;
        private final double usdPrice;
        private final List<String> productIds;

        Tier(String suffix, double usdPrice, List<String> productIds) {
            this.suffix = suffix;
            this.usdPrice = usdPrice;
            this.productIds = productIds;
        }

        String getSuffix() {
            return suffix;
        }

        double getUsdPrice() {
            return usdPrice;
        }

        List<String> getProductIds() {
            return productIds;
        }
    }

    /** A resolved offer: how many coins are on sale, and the price point / products that sell them. */
    static final class Resolved {
        private final int coins;
        private final String tier;
        private final double usdPrice;
        private final List<String> productIds;

        Resolved(int coins, String tier, double usdPrice, List<String> productIds) {
            this.coins = coins;
            this.tier = tier;
            this.usdPrice = usdPrice;
            this.productIds = productIds;
        }

        int getCoins() {
            return coins;
        }

        String getTier() {
            return tier;
        }

        double getUsdPrice() {
            return usdPrice;
        }

        List<String> getProductIds() {
            return productIds;
        }

        boolean sells(String productId) {
            return productIds.contains(productId);
        }
    }

    /**
     * The price-point ladder derived from the live {@code aicoin:iap-packages} catalog, ascending
     * by price. Entries are grouped by tier suffix; where one tier's apps disagree on price (a
     * half-applied catalog edit), the <b>highest</b> is used, since under-pricing an offer is the
     * failure that costs real money and over-pricing merely sells at the next point up.
     */
    @SuppressWarnings("unchecked")
    static List<Tier> tiers(String packagesJson) {
        Object parsed;
        try {
            parsed = new Yaml().load(packagesJson);
        } catch (Exception e) {
            return List.of();
        }
        if (!(parsed instanceof List)) {
            return List.of();
        }
        Map<String, Double> priceBySuffix = new LinkedHashMap<>();
        Map<String, List<String>> productsBySuffix = new LinkedHashMap<>();
        for (Object item : (List<Object>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> map = (Map<?, ?>) item;
            Object productId = map.get("product_id");
            Object usdPriceHint = map.get("usd_price_hint");
            if (!(productId instanceof String) || !(usdPriceHint instanceof Number)) {
                continue;
            }
            String suffix = tierSuffix((String) productId);
            if (suffix == null) {
                continue;
            }
            double price = ((Number) usdPriceHint).doubleValue();
            if (price <= 0) {
                // A price point of $0 would let any coin amount resolve to it for free.
                continue;
            }
            priceBySuffix.merge(suffix, price, Math::max);
            productsBySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add((String) productId);
        }
        List<Tier> tiers = new ArrayList<>();
        for (Map.Entry<String, Double> entry : priceBySuffix.entrySet()) {
            tiers.add(new Tier(entry.getKey(), entry.getValue(), List.copyOf(productsBySuffix.get(entry.getKey()))));
        }
        tiers.sort(Comparator.comparingDouble(Tier::getUsdPrice));
        return List.copyOf(tiers);
    }

    /** The {@code <tier>} of a {@code <prefix>.aicoin.<tier>} product id, or null if it doesn't fit that shape. */
    static String tierSuffix(String productId) {
        int at = productId.lastIndexOf(TIER_INFIX);
        if (at < 0) {
            return null;
        }
        String suffix = productId.substring(at + TIER_INFIX.length());
        if (suffix.isEmpty() || suffix.indexOf('.') >= 0) {
            return null;
        }
        return suffix;
    }

    /**
     * Resolves {@code coins} against the live price signal: prices the coins with the same
     * formula the repricer uses ({@link AppStorePriceRounding#rawPrice}), then takes the cheapest
     * price point that covers it. {@link Optional#empty()} when no point does — i.e. the operator
     * asked to sell more coins than the most expensive product can pay for, which must be an
     * error rather than a silent clamp to the top tier (that would sell the excess for nothing).
     */
    static Optional<Resolved> resolveByPrice(String packagesJson, int coins, double priceUsd) {
        double rawPrice = AppStorePriceRounding.rawPrice(coins, priceUsd);
        for (Tier tier : tiers(packagesJson)) {
            if (tier.getUsdPrice() + PRICE_EPSILON >= rawPrice) {
                return Optional.of(new Resolved(coins, tier.getSuffix(), tier.getUsdPrice(), tier.getProductIds()));
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves {@code coins} at an operator-chosen price point instead of the computed one — the
     * escape hatch for when the price signal is too thin to trust (a fresh deploy reports {@code
     * price_usd == 0}, which would otherwise price every offer at the cheapest point). Picks the
     * point matching {@code usdPrice}, or the cheapest one above it if it isn't an exact match.
     */
    static Optional<Resolved> resolveAtPrice(String packagesJson, int coins, double usdPrice) {
        for (Tier tier : tiers(packagesJson)) {
            if (tier.getUsdPrice() + PRICE_EPSILON >= usdPrice) {
                return Optional.of(new Resolved(coins, tier.getSuffix(), tier.getUsdPrice(), tier.getProductIds()));
            }
        }
        return Optional.empty();
    }

    /** The stored/served shape of an offer — {@code aicoin:offer}'s value and {@code GET /iap/offer}'s {@code offer} field. */
    static String toJson(Resolved resolved, long setAtMillis) {
        StringBuilder sb = new StringBuilder("{\"coins\":").append(resolved.getCoins())
                .append(",\"tier\":").append(jsonString(resolved.getTier()))
                .append(",\"usd_price\":").append(formatNumber(resolved.getUsdPrice()))
                .append(",\"product_ids\":[");
        boolean first = true;
        for (String productId : resolved.getProductIds()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(jsonString(productId));
        }
        return sb.append("],\"set_at\":").append(setAtMillis).append("}").toString();
    }

    /**
     * Reads back what {@link #toJson} wrote — used by {@code POST /wallet/api/redeem-iap} to find
     * the coin amount a purchase is worth, both for the live offer and for a pinned one.
     */
    @SuppressWarnings("unchecked")
    static Optional<Resolved> parse(String offerJson) {
        if (offerJson == null || offerJson.isEmpty()) {
            return Optional.empty();
        }
        Object parsed;
        try {
            parsed = new Yaml().load(offerJson);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map)) {
            return Optional.empty();
        }
        Map<?, ?> map = (Map<?, ?>) parsed;
        Object coins = map.get("coins");
        Object tier = map.get("tier");
        Object usdPrice = map.get("usd_price");
        Object productIds = map.get("product_ids");
        if (!(coins instanceof Number) || !isPositiveInteger((Number) coins) || !(productIds instanceof List)) {
            return Optional.empty();
        }
        List<String> ids = new ArrayList<>();
        for (Object id : (List<Object>) productIds) {
            if (id instanceof String) {
                ids.add((String) id);
            }
        }
        return Optional.of(new Resolved(
                ((Number) coins).intValue(),
                tier instanceof String ? (String) tier : "",
                usdPrice instanceof Number ? ((Number) usdPrice).doubleValue() : 0.0,
                List.copyOf(ids)));
    }

    private static boolean isPositiveInteger(Number n) {
        double d = n.doubleValue();
        return d > 0 && d == Math.rint(d);
    }

    private static String formatNumber(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
