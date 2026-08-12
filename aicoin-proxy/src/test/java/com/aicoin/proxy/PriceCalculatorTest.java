package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mirrors the Go node's old {@code state_test.go} checkpoint table for the
 * recency-weighted price formula, per CONTRACT.md's "Price (final formula...)"
 * section: {@code weight(age_days) = 2^(-age_days/halfLifeDays)}, default
 * half-life 110 days.
 */
class PriceCalculatorTest {

    private static final double HALF_LIFE_DAYS = 110.0;
    private static final double DELTA = 1e-3;

    @Test
    void weightCheckpointsMatchDocumentedTable() {
        assertEquals(1.000, PriceCalculator.weight(1.0 / 24, HALF_LIFE_DAYS), DELTA); // 1 hour
        assertEquals(0.994, PriceCalculator.weight(1, HALF_LIFE_DAYS), DELTA); // 1 day
        assertEquals(0.957, PriceCalculator.weight(7, HALF_LIFE_DAYS), DELTA); // 1 week
        assertEquals(0.825, PriceCalculator.weight(30.44, HALF_LIFE_DAYS), DELTA); // 1 month
        assertEquals(0.563, PriceCalculator.weight(91.31, HALF_LIFE_DAYS), DELTA); // 1 quarter
        assertEquals(0.100, PriceCalculator.weight(365.25, HALF_LIFE_DAYS), DELTA); // 1 year
        assertEquals(0.00001, PriceCalculator.weight(365.25 * 5, HALF_LIFE_DAYS), 1e-5); // 5 years
    }

    @Test
    void negativeAgeClampsToWeightOne() {
        assertEquals(1.0, PriceCalculator.weight(-5, HALF_LIFE_DAYS), 1e-12);
    }

    @Test
    void zeroEventsYieldsAllZeroPrice() {
        AicoinLedger.PriceResult result = PriceCalculator.compute(Collections.emptyList(), 0, HALF_LIFE_DAYS);
        assertEquals(0, result.getPriceUsd(), 1e-12);
        assertEquals(0, result.getTotalSpendUsd(), 1e-12);
        assertEquals(0, result.getWeightedTotal(), 1e-12);
    }

    @Test
    void priceIsWeightedAverageAtHalfLifeMultiples() {
        double now = 0;
        double dayMillis = 86_400_000.0;
        List<PriceCalculator.Event> events = List.of(
                new PriceCalculator.Event(1.0, now), // age 0 -> weight 1.0
                new PriceCalculator.Event(1.0, now - HALF_LIFE_DAYS * dayMillis), // age 1 half-life -> weight 0.5
                new PriceCalculator.Event(1.0, now - 2 * HALF_LIFE_DAYS * dayMillis) // age 2 half-lives -> weight 0.25
        );
        AicoinLedger.PriceResult result = PriceCalculator.compute(events, now, HALF_LIFE_DAYS);

        double expectedWeightedTotal = 1.0 + 0.5 + 0.25;
        double expectedWeightedSum = 1.0 * 1.0 + 1.0 * 0.5 + 1.0 * 0.25;
        assertEquals(expectedWeightedTotal, result.getWeightedTotal(), DELTA);
        assertEquals(expectedWeightedSum / expectedWeightedTotal, result.getPriceUsd(), DELTA);
        assertEquals(3.0, result.getTotalSpendUsd(), 1e-12);
    }

    @Test
    void customHalfLifeChangesTotalSpendWeightButNotSingleEventPrice() {
        // With a single event, price_usd = weight*cost / weight = cost regardless
        // of half-life (weight cancels out in the average) — but weightedTotal
        // itself (the formula's denominator) does change with half-life, which is
        // what actually differs and is worth asserting on.
        List<PriceCalculator.Event> events = List.of(new PriceCalculator.Event(2.0, 0));
        AicoinLedger.PriceResult shortHalfLife = PriceCalculator.compute(events, 30 * 86_400_000.0, 10.0);
        AicoinLedger.PriceResult longHalfLife = PriceCalculator.compute(events, 30 * 86_400_000.0, 200.0);

        assertEquals(2.0, shortHalfLife.getPriceUsd(), DELTA);
        assertEquals(2.0, longHalfLife.getPriceUsd(), DELTA);
        assertEquals(PriceCalculator.weight(30, 10.0), shortHalfLife.getWeightedTotal(), DELTA);
        assertEquals(PriceCalculator.weight(30, 200.0), longHalfLife.getWeightedTotal(), DELTA);
        // A shorter half-life decays the 30-day-old event's weight faster.
        assertTrue(shortHalfLife.getWeightedTotal() < longHalfLife.getWeightedTotal());
    }

    @Test
    void sizelessEventsPriceExactlyAsBefore() {
        // Every event recorded before sizes were persisted has none, and the formula must be
        // bit-for-bit what it was for them — otherwise deploying this would reprice the existing
        // ledger. Two costs, two ages, no sizes: the plain recency-weighted mean.
        double now = 10 * 86_400_000.0;
        List<PriceCalculator.Event> events = List.of(
                new PriceCalculator.Event(0.02, now),
                new PriceCalculator.Event(0.01, now - HALF_LIFE_DAYS * 86_400_000.0));

        AicoinLedger.PriceResult result = PriceCalculator.compute(events, now, HALF_LIFE_DAYS);

        assertEquals((0.02 * 1.0 + 0.01 * 0.5) / 1.5, result.getPriceUsd(), 1e-9);
        assertEquals(1.5, result.getWeightedTotal(), 1e-9);
    }

    @Test
    void uniformSizesPriceTheSameAsNoSizes() {
        // Size only matters relative to other sizes. When every call is the same size the size
        // terms cancel and the answer must not move.
        double now = 0;
        List<PriceCalculator.Event> sized = List.of(
                new PriceCalculator.Event(0.02, now, 1000, true),
                new PriceCalculator.Event(0.04, now, 1000, true));
        List<PriceCalculator.Event> unsized = List.of(
                new PriceCalculator.Event(0.02, now),
                new PriceCalculator.Event(0.04, now));

        assertEquals(PriceCalculator.compute(unsized, now, HALF_LIFE_DAYS).getPriceUsd(),
                PriceCalculator.compute(sized, now, HALF_LIFE_DAYS).getPriceUsd(), 1e-12);
    }

    @Test
    void bigCallsCountForMoreThanSmallOnes() {
        // The point of the change: one 100,000-token call at $1.00 and ninety-nine 100-token calls
        // at $0.001. Per call, the mean is dragged to nearly nothing by the ninety-nine; per token,
        // the expensive call dominates because it is where essentially all the work happened.
        double now = 0;
        List<PriceCalculator.Event> events = new java.util.ArrayList<>();
        events.add(new PriceCalculator.Event(1.00, now, 100_000, true));
        for (int i = 0; i < 99; i++) {
            events.add(new PriceCalculator.Event(0.001, now, 100, true));
        }

        double perCallMean = (1.00 + 99 * 0.001) / 100;
        double priced = PriceCalculator.compute(events, now, HALF_LIFE_DAYS).getPriceUsd();

        assertTrue(priced > perCallMean * 5,
                "token weighting should not collapse to the per-call mean: " + priced);
        // Cost per token is 1.00/100000 for the big call and 0.001/100 for each small one; the
        // big call carries 100000/(100000+9900) of the weight.
        double meanSize = (100_000 + 99 * 100) / 100.0;
        double expected = (1.00 + 99 * 0.001) / (100_000 + 99 * 100.0) * meanSize;
        assertEquals(expected, priced, 1e-9);
    }

    @Test
    void unknownSizedEventsTakeTheMeanKnownSizeRatherThanVanishing() {
        // A speech call reports no tokens. Weighting it by 1 would erase it beside a 1,000-token
        // call; dropping it would discard real spend. It stands in at the mean known size, so it
        // carries exactly the weight of a typical call.
        double now = 0;
        List<PriceCalculator.Event> events = List.of(
                new PriceCalculator.Event(0.01, now, 1000, true),
                new PriceCalculator.Event(0.05, now));

        double priced = PriceCalculator.compute(events, now, HALF_LIFE_DAYS).getPriceUsd();

        // Both end up size 1000, so this is the plain mean of the two costs.
        assertEquals(0.03, priced, 1e-9);
        assertEquals(2.0, PriceCalculator.compute(events, now, HALF_LIFE_DAYS).getWeightedTotal(), 1e-9);
    }

    @Test
    void weightedTotalStaysAnEventCountNotATokenCount() {
        // weighted_total is published and gates offer pricing at ">= 50" meaning "enough recent
        // calls". If sizes leaked into it, three calls would read as tens of thousands and that
        // guard would never fire again.
        double now = 0;
        List<PriceCalculator.Event> events = List.of(
                new PriceCalculator.Event(0.01, now, 50_000, true),
                new PriceCalculator.Event(0.01, now, 60_000, true),
                new PriceCalculator.Event(0.01, now, 70_000, true));

        assertEquals(3.0, PriceCalculator.compute(events, now, HALF_LIFE_DAYS).getWeightedTotal(), 1e-9);
    }
}
