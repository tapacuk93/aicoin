package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The rounding rule for metered billing, pinned against real measured call costs.
 *
 * <p>Every case here uses a figure this proxy actually recorded for a live call, so the test also
 * documents what metering would charge for the traffic it sees today.
 */
class CoinMeterTest {

    /** The per-coin price of the largest IAP pack — see `pricing.coinValueUsd`. */
    private static final double COIN = 0.009;

    @Test
    void aSubCoinCallStillCostsOneCoin() {
        // A haiku image-search call, measured at ~$0.003 — a third of a coin.
        assertEquals(1L, CoinMeter.coinsFor(0.00297, COIN));
    }

    @Test
    void anExpensiveCallCostsWhatItCost() {
        // Measured segments: $0.019 at the start of a station, $0.051 six deep.
        assertEquals(3L, CoinMeter.coinsFor(0.01912, COIN));
        assertEquals(6L, CoinMeter.coinsFor(0.05058, COIN));
    }

    @Test
    void cachingShowsUpAsFewerCoins() {
        // The same six-segments-deep call, with the conversation prefix cached: $0.0252.
        assertEquals(3L, CoinMeter.coinsFor(0.0252, COIN));
        assertTrue(CoinMeter.coinsFor(0.0252, COIN) < CoinMeter.coinsFor(0.05058, COIN),
                "a cheaper call must cost the listener fewer coins, or metering buys nothing");
    }

    @Test
    void roundingIsUpwardsNotNearest() {
        assertEquals(2L, CoinMeter.coinsFor(0.0091, COIN), "a hair over one coin is two, not one");
        assertEquals(1L, CoinMeter.coinsFor(COIN, COIN), "exactly one coin is one coin");
    }

    @Test
    void oneCoinIsAlwaysEnoughToMakeACall() {
        // The floor is what keeps the wallet gate honest: the gate holds exactly one coin, so
        // metering must never conclude a call was worth less than that and leave the hold
        // over-charged, nor let a free-looking call bypass it.
        assertEquals(1L, CoinMeter.coinsFor(0.0, COIN));
        assertEquals(1L, CoinMeter.coinsFor(0.0000001, COIN));
    }

    @Test
    void nonsenseInputsFallBackToTheFlatCharge() {
        assertEquals(1L, CoinMeter.coinsFor(Double.NaN, COIN));
        assertEquals(1L, CoinMeter.coinsFor(Double.POSITIVE_INFINITY, COIN));
        assertEquals(1L, CoinMeter.coinsFor(0.05, 0.0), "a zero coin value must not divide by zero");
        assertEquals(1L, CoinMeter.coinsFor(0.05, -1), "a negative coin value must not credit the wallet");
    }

    @Test
    void oneCallCannotEmptyAWallet() {
        // A provider reporting nonsense usage, or a rate configured in dollars-per-token by
        // mistake, must not turn one call into an unbounded charge.
        assertEquals(CoinMeter.MAX_COINS_PER_CALL, CoinMeter.coinsFor(1_000_000.0, COIN));
    }
}
