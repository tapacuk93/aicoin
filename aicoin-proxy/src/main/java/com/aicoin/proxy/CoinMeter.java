package com.aicoin.proxy;

/**
 * Turns a call's real dollar cost into a whole number of aicoin.
 *
 * <p>The flat one-coin-per-call rule prices a call the same whether it cost a fifth of a coin or
 * five: measured through this proxy, a short lookup runs about $0.003 while a long-context turn
 * runs past $0.05, and a conversation-replaying client's calls get steadily dearer the longer the
 * conversation goes. Flat-rating that means cheap calls subsidize expensive ones and the operator
 * carries whatever the difference turns out to be.
 *
 * <p>Metering charges what the call actually cost, rounded UP to a whole coin with a floor of one.
 * Whole coins because a wallet that shows "3 coins" and can't afford a 2.4-coin call is a worse
 * experience than paying a rounded price; the floor because a call that reaches a provider always
 * costs the operator something, and because it preserves the property every client already relies
 * on — one coin in the wallet is enough to make one call.
 *
 * <p>Rounding up systematically favours the operator, which is the safe direction for a rule that
 * has to hold across providers whose prices this proxy learns only after the fact — but it does
 * mean a sub-coin call is charged a full coin, exactly as it is today.
 */
final class CoinMeter {

    private CoinMeter() {
    }

    /**
     * @param costUsd      the call's computed cost — see {@link CostCalculator}
     * @param coinValueUsd what one aicoin is taken to be worth, from {@code pricing.coinValueUsd}
     * @return whole coins to charge, never less than one
     */
    static long coinsFor(double costUsd, double coinValueUsd) {
        if (!(coinValueUsd > 0) || !(costUsd > 0) || Double.isNaN(costUsd) || Double.isInfinite(costUsd)) {
            // A missing or nonsensical rate must not make calls free, and must not make them
            // arbitrarily expensive either — fall back to the flat charge.
            return 1L;
        }
        double coins = Math.ceil(costUsd / coinValueUsd);
        if (coins < 1) {
            return 1L;
        }
        // Bounded so one absurd cost figure — a provider reporting nonsense usage, a misconfigured
        // rate — can't empty a wallet in a single call. The cap is far above any real call.
        return (long) Math.min(coins, MAX_COINS_PER_CALL);
    }

    /** Ceiling on what a single call may charge, whatever the arithmetic says. */
    static final long MAX_COINS_PER_CALL = 100L;
}
