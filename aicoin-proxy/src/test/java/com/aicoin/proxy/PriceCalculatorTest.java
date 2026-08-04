package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Mirrors the Go node's old {@code state_test.go} checkpoint table for the
 * recency-weighted price formula, per CONTRACT.md's "Derived state — price"
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
}
