package com.aicoin.proxy;

/**
 * Coins per minute for a hosted phone, from what an hour of its instance costs the operator.
 *
 * <p>The same conventions the proxy prices everything else by, applied to a cost known in advance
 * instead of after the call:
 * <ul>
 *   <li>A coin is worth {@code pricing.coinValueUsd} — the per-coin price of the largest IAP pack,
 *       the cheapest a coin is ever sold for (see {@link CoinMeter}).</li>
 *   <li>The operator keeps {@link AppStorePriceRounding#FEE_MARGIN} over raw cost, and Apple takes
 *       {@link AppStorePriceRounding#APPLE_CUT} of every sale — the factors the IAP repricer prices
 *       coin packs with ({@code × (1 + feeMargin) / (1 − appleCut)}).</li>
 *   <li>Whole coins, rounded up, never less than one — {@link CoinMeter}'s rule.</li>
 * </ul>
 *
 * <p>Why the margin factors are applied here when a metered AI call does not apply them: an AI
 * call's {@code cost_usd / coinValueUsd} is a few coins, and the IAP price signal it feeds
 * ({@code price_usd}) is what carries the margin into the pack prices. A phone's spend is not fed
 * into that signal (an hour of a GPU instance would swamp the per-call average every AI price
 * is derived from), so the margin has to be in the per-minute rate itself — otherwise every coin
 * spent on a phone would return 70% of its AWS cost after Apple's cut.
 */
final class PhonePricing {

    private PhonePricing() {
    }

    /** The multiplier from the operator's raw cost to what the coins must bring in. */
    static double markup(double feeMargin) {
        return (1 + feeMargin) / (1 - AppStorePriceRounding.APPLE_CUT);
    }

    static long coinsPerMinute(double usdPerHour, double coinValueUsd, double feeMargin) {
        if (!(usdPerHour > 0) || !(coinValueUsd > 0) || Double.isInfinite(usdPerHour)) {
            // A missing rate must not make a phone free.
            return 1L;
        }
        double coins = usdPerHour / 60.0 * markup(feeMargin) / coinValueUsd;
        // Tolerance so a rate that divides exactly is not pushed up a coin by float error.
        return Math.max(1L, (long) Math.ceil(coins - 1e-9));
    }

    static long coinsPerMinute(AnyphoneConfig.Tier tier, AnyphoneConfig config) {
        return coinsPerMinute(tier.getUsdPerHour(), config.getCoinValueUsd(), config.getFeeMargin());
    }

    /** Minutes started by {@code nowMillis} for a phone launched at {@code launchedAtMillis}: the minute in progress counts. */
    static long startedMinutes(long launchedAtMillis, long nowMillis) {
        if (nowMillis < launchedAtMillis) {
            return 1L;
        }
        return (nowMillis - launchedAtMillis) / 60_000L + 1L;
    }
}
