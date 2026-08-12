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

    /** A single priced event: its cost, size, and the epoch-millis timestamp it was recorded at. */
    static final class Event {
        final double costUsd;
        final double timestampMillis;
        /** Total billable tokens; meaningful only when {@link #tokensKnown}. */
        final long tokens;
        final boolean tokensKnown;

        Event(double costUsd, double timestampMillis) {
            this(costUsd, timestampMillis, 0, false);
        }

        Event(double costUsd, double timestampMillis, long tokens, boolean tokensKnown) {
            this.costUsd = costUsd;
            this.timestampMillis = timestampMillis;
            this.tokens = tokens;
            this.tokensKnown = tokensKnown;
        }
    }

    static double weight(double ageDays, double halfLifeDays) {
        return Math.pow(2, -Math.max(0, ageDays) / halfLifeDays);
    }

    static AicoinLedger.PriceResult compute(List<Event> events, double nowMillis, double halfLifeDays) {
        // Size enters the weight, not just recency. Averaging per call makes a 200-token lookup
        // count as much as a 200,000-token turn, so the reported price tracks the *mix* of call
        // sizes rather than the cost of the work — and it drifts whenever that mix moves, without
        // any underlying price having changed. Weighting by tokens states the cost of the tokens
        // actually served, which is what metered billing charges against.
        //
        // Events whose response reported no usage at all (speech, image) have no size to weight
        // by. Dropping them would discard real spend, and giving them a size of 1 would make them
        // vanish beside any real call, so they take the recency-weighted mean size of the events
        // that do know theirs — the least-assuming stand-in available. When nothing knows its
        // size, every weight collapses to the recency term and this reduces exactly to the
        // per-call formula it replaces, which is also what every event recorded before sizes were
        // persisted does.
        double knownSizeWeighted = 0;
        double knownWeight = 0;
        for (Event event : events) {
            if (event.tokensKnown && event.tokens > 0) {
                double w = weight((nowMillis - event.timestampMillis) / MILLIS_PER_DAY, halfLifeDays);
                knownSizeWeighted += w * event.tokens;
                knownWeight += w;
            }
        }
        double imputedSize = knownWeight > 0 ? knownSizeWeighted / knownWeight : 1;

        double totalSpend = 0;
        double weightedCost = 0;
        double weightedSize = 0;
        double weightedTotal = 0;
        for (Event event : events) {
            double ageDays = (nowMillis - event.timestampMillis) / MILLIS_PER_DAY;
            double w = weight(ageDays, halfLifeDays);
            double size = event.tokensKnown && event.tokens > 0 ? event.tokens : imputedSize;
            totalSpend += event.costUsd;
            weightedCost += w * event.costUsd;
            weightedSize += w * size;
            // Deliberately still Σweight, not Σ(weight × size): weighted_total is published and
            // gates offer pricing at ">= 50", a threshold that means "enough recent calls". Scaling
            // it by tokens would put it in the millions and silently disable that guard.
            weightedTotal += w;
        }
        // Dollars per token, restored to dollars per call of average size — so price_usd keeps its
        // units and its meaning, while what it averages over is tokens served rather than calls
        // made. With uniform or unknown sizes the size terms cancel and this is exactly the
        // per-call formula it replaces.
        double priceUsd = weightedSize > 0 ? weightedCost / weightedSize * imputedSize : 0;
        return new AicoinLedger.PriceResult(priceUsd, totalSpend, weightedTotal, halfLifeDays);
    }
}
