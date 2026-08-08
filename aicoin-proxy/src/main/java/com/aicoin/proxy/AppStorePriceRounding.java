package com.aicoin.proxy;

import java.util.Arrays;

/**
 * Reference implementation of the price-point rounding math CONTRACT.md's "Automatic price
 * adjustment" section specifies: {@code coins * price_usd * (1 + feeMargin) / (1 - appleCut)},
 * rounded to the nearest standard App Store {@code $X.99} price point. This class is not wired
 * into any HTTP endpoint or called at runtime by the Java proxy — the actual hourly job
 * ({@code scripts/adjust-iap-prices.sh}) runs standalone via cron on the production host, with no
 * JVM involved, so it re-implements this same arithmetic directly in bash. This class exists
 * purely so the formula and the rounding-to-nearest-tier logic are verified once, here, in a
 * strongly-typed language with a real test suite ({@code AppStorePriceRoundingTest}) — a
 * deliberate, documented duplication, not dead code: the shell script's arithmetic should be kept
 * in sync with this class's whenever either changes.
 */
final class AppStorePriceRounding {

    /** Apple's 30% cut of consumable IAP revenue, flat regardless of app age — CONTRACT.md's "Recommended launch packages". */
    static final double APPLE_CUT = 0.30;
    /** The margin above raw AI cost this proxy's operator keeps — CONTRACT.md's "Recommended launch packages". */
    static final double FEE_MARGIN = 0.50;

    /**
     * A representative subset of Apple's standard USD App Store price tiers (all ending in
     * {@code .99}), covering the range this formula actually produces for the launch coin
     * amounts (50/200/1,000/5,000). Apple's real tier list has ~100+ entries across every
     * currency with a non-formulaic progression; this is not the complete list, just enough of
     * it, evenly documented, to round a computed price to "a normal-looking App Store price
     * point" per CONTRACT.md.
     */
    private static final double[] PRICE_TIERS = {
            0.99, 1.99, 2.99, 3.99, 4.99, 5.99, 6.99, 7.99, 8.99, 9.99,
            11.99, 13.99, 15.99, 17.99, 19.99, 24.99, 29.99, 34.99, 39.99, 44.99,
            49.99, 59.99, 69.99, 79.99, 89.99, 99.99, 119.99, 149.99, 199.99
    };

    private AppStorePriceRounding() {
    }

    /**
     * The raw, unrounded target USD price for a package of {@code coins} aicoin, given the
     * current (or estimated) per-call {@code priceUsd} signal.
     */
    static double rawPrice(int coins, double priceUsd) {
        return coins * priceUsd * (1 + FEE_MARGIN) / (1 - APPLE_CUT);
    }

    /** {@link #rawPrice} rounded to the nearest entry in {@link #PRICE_TIERS}. */
    static double roundToNearestTier(double rawPrice) {
        double best = PRICE_TIERS[0];
        double bestDistance = Math.abs(rawPrice - best);
        for (double tier : PRICE_TIERS) {
            double distance = Math.abs(rawPrice - tier);
            if (distance < bestDistance) {
                best = tier;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** {@link #roundToNearestTier} applied directly to {@link #rawPrice} — the one call site {@code scripts/adjust-iap-prices.sh} mirrors in bash. */
    static double targetPrice(int coins, double priceUsd) {
        return roundToNearestTier(rawPrice(coins, priceUsd));
    }

    /** Exposed only for tests asserting against the exact documented tier ladder. */
    static double[] priceTiers() {
        return Arrays.copyOf(PRICE_TIERS, PRICE_TIERS.length);
    }
}
