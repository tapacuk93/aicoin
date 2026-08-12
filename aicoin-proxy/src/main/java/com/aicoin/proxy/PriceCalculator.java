package com.aicoin.proxy;

import java.util.List;

/**
 * Pure recency-weighted price math, per CONTRACT.md's "Price (final formula,
 * v2: smooth exponential decay)" under "Ledger (Redis)" — no I/O, so
 * it can be unit-tested without a live Redis connection. {@link
 * AicoinLedger#computePrice} feeds this the raw event list fetched from
 * Redis.
 *
 * <p>{@code price_usd = Σ(weight(age_i) * cost_usd_i) / Σ(weight(age_i))}
 * over every priced AI call ever recorded, where {@code weight(age_days) =
 * 2^(-age_days/halfLifeDays)} — a negative age (clock skew) clamps to a
 * weight of 1.0. Zero events yields a price of 0.
 */
final class PriceCalculator {

    private static final double MILLIS_PER_DAY = 86_400_000.0;

    private PriceCalculator() {
    }

    /** A single priced event: its cost and the epoch-millis timestamp it was recorded at. */
    static final class Event {
        final double costUsd;
        final double timestampMillis;

        Event(double costUsd, double timestampMillis) {
            this.costUsd = costUsd;
            this.timestampMillis = timestampMillis;
        }
    }

    static double weight(double ageDays, double halfLifeDays) {
        return Math.pow(2, -Math.max(0, ageDays) / halfLifeDays);
    }

    static AicoinLedger.PriceResult compute(List<Event> events, double nowMillis, double halfLifeDays) {
        double totalSpend = 0;
        double weightedSum = 0;
        double weightedTotal = 0;
        for (Event event : events) {
            double ageDays = (nowMillis - event.timestampMillis) / MILLIS_PER_DAY;
            double w = weight(ageDays, halfLifeDays);
            totalSpend += event.costUsd;
            weightedSum += w * event.costUsd;
            weightedTotal += w;
        }
        double priceUsd = weightedTotal > 0 ? weightedSum / weightedTotal : 0;
        return new AicoinLedger.PriceResult(priceUsd, totalSpend, weightedTotal, halfLifeDays);
    }
}
