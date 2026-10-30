package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PhonePricingTest {

    private static final double COIN = 0.009;
    private static final double MARGIN = AppStorePriceRounding.FEE_MARGIN;

    @Test
    void markupIsTheIapRepricersFactor() {
        // (1 + 0.5) / (1 - 0.3)
        assertEquals(1.5 / 0.7, PhonePricing.markup(MARGIN), 1e-12);
    }

    @Test
    void cpuPhoneIsTwoCoinsAMinute() {
        // $0.38/h -> $0.006333/min -> x2.142857 = $0.013571 -> /0.009 = 1.508 -> 2
        assertEquals(2, PhonePricing.coinsPerMinute(0.38, COIN, MARGIN));
    }

    @Test
    void gpuPhoneOnDemandAndSpot() {
        // $7.82/h -> $0.130333/min -> x2.142857 = $0.279286 -> /0.009 = 31.03 -> 32
        assertEquals(32, PhonePricing.coinsPerMinute(7.82, COIN, MARGIN));
        // $4.50/h -> $0.075/min -> $0.160714 -> 17.86 -> 18
        assertEquals(18, PhonePricing.coinsPerMinute(4.50, COIN, MARGIN));
    }

    @Test
    void anExactRateIsNotRoundedUpByFloatError() {
        // A rate chosen to come to exactly three coins a minute at no margin.
        double usdPerHour = 60 * 0.009 * 0.7 * 3; // three coins a minute at margin 0
        assertEquals(3, PhonePricing.coinsPerMinute(usdPerHour, COIN, 0.0));
    }

    @Test
    void neverFreeAndNeverBrokenByBadConfig() {
        assertEquals(1, PhonePricing.coinsPerMinute(0.0001, COIN, MARGIN));
        assertEquals(1, PhonePricing.coinsPerMinute(0, COIN, MARGIN));
        assertEquals(1, PhonePricing.coinsPerMinute(1.0, 0, MARGIN));
        assertEquals(1, PhonePricing.coinsPerMinute(Double.NaN, COIN, MARGIN));
    }

    @Test
    void theTierRateUsesTheConfiguredCoinValueAndMargin() {
        AnyphoneConfig config = AnyphoneConfig.disabled(COIN);
        assertEquals(32, PhonePricing.coinsPerMinute(config.tier("gpu"), config));
        assertEquals(2, PhonePricing.coinsPerMinute(config.tier("cpu"), config));
    }

    @Test
    void everyStartedMinuteCounts() {
        long t0 = 1_000_000L;
        assertEquals(1, PhonePricing.startedMinutes(t0, t0));
        assertEquals(1, PhonePricing.startedMinutes(t0, t0 + 59_999));
        assertEquals(2, PhonePricing.startedMinutes(t0, t0 + 60_000));
        assertEquals(11, PhonePricing.startedMinutes(t0, t0 + 10 * 60_000 + 1));
        // A clock that steps back never un-charges or under-counts below the first minute.
        assertEquals(1, PhonePricing.startedMinutes(t0, t0 - 5_000));
    }
}
