package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link CoinOffer}'s pure offer math: deriving the fixed price-point ladder from the
 * package catalog, resolving a coin amount onto it, and the JSON round-trip {@code aicoin:offer}
 * and the pins are stored as.
 */
class CoinOfferTest {

    /** The live catalog shape: 4 tiers x 3 apps, the four fixed price points of CONTRACT.md's IAP section. */
    private static final String CATALOG = "["
            + "{\"product_id\":\"com.tarasmaslov.infiniteairadio.aicoin.small\",\"coins\":50,\"usd_price_hint\":0.99},"
            + "{\"product_id\":\"com.tarasmaslov.infiniteairadio.aicoin.medium\",\"coins\":200,\"usd_price_hint\":2.99},"
            + "{\"product_id\":\"com.tarasmaslov.infiniteairadio.aicoin.large\",\"coins\":1000,\"usd_price_hint\":9.99},"
            + "{\"product_id\":\"com.tarasmaslov.infiniteairadio.aicoin.xl\",\"coins\":5000,\"usd_price_hint\":44.99},"
            + "{\"product_id\":\"com.tarasmaslov.alllanguageslearner.aicoin.small\",\"coins\":50,\"usd_price_hint\":0.99},"
            + "{\"product_id\":\"com.tarasmaslov.alllanguageslearner.aicoin.medium\",\"coins\":200,\"usd_price_hint\":2.99},"
            + "{\"product_id\":\"com.tarasmaslov.alllanguageslearner.aicoin.large\",\"coins\":1000,\"usd_price_hint\":9.99},"
            + "{\"product_id\":\"com.tarasmaslov.alllanguageslearner.aicoin.xl\",\"coins\":5000,\"usd_price_hint\":44.99},"
            + "{\"product_id\":\"com.tarasmaslov.learnit.aicoin.small\",\"coins\":50,\"usd_price_hint\":0.99},"
            + "{\"product_id\":\"com.tarasmaslov.learnit.aicoin.medium\",\"coins\":200,\"usd_price_hint\":2.99},"
            + "{\"product_id\":\"com.tarasmaslov.learnit.aicoin.large\",\"coins\":1000,\"usd_price_hint\":9.99},"
            + "{\"product_id\":\"com.tarasmaslov.learnit.aicoin.xl\",\"coins\":5000,\"usd_price_hint\":44.99}]";

    // ---- tiers ----

    @Test
    void tiersCollapseTwelveProductsIntoFourPricePointsAscending() {
        List<CoinOffer.Tier> tiers = CoinOffer.tiers(CATALOG);

        assertEquals(4, tiers.size());
        assertEquals(List.of("small", "medium", "large", "xl"),
                tiers.stream().map(CoinOffer.Tier::getSuffix).toList());
        assertEquals(List.of(0.99, 2.99, 9.99, 44.99),
                tiers.stream().map(CoinOffer.Tier::getUsdPrice).toList());
    }

    @Test
    void eachTierCarriesAllThreeAppsProductIds() {
        CoinOffer.Tier large = CoinOffer.tiers(CATALOG).get(2);

        assertEquals(List.of(
                "com.tarasmaslov.infiniteairadio.aicoin.large",
                "com.tarasmaslov.alllanguageslearner.aicoin.large",
                "com.tarasmaslov.learnit.aicoin.large"), large.getProductIds());
    }

    /** A half-applied catalog edit must not let an offer resolve onto the cheaper of two disagreeing prices. */
    @Test
    void aTierWhoseAppsDisagreeOnPriceTakesTheHighest() {
        String skewed = "[{\"product_id\":\"a.aicoin.large\",\"coins\":1,\"usd_price_hint\":9.99},"
                + "{\"product_id\":\"b.aicoin.large\",\"coins\":1,\"usd_price_hint\":19.99}]";

        assertEquals(19.99, CoinOffer.tiers(skewed).get(0).getUsdPrice());
    }

    @Test
    void productsThatArentTierShapedOrArePricedAtZeroAreIgnored() {
        String odd = "[{\"product_id\":\"no-tier-here\",\"coins\":1,\"usd_price_hint\":9.99},"
                + "{\"product_id\":\"a.aicoin.free\",\"coins\":1,\"usd_price_hint\":0},"
                + "{\"product_id\":\"a.aicoin.small\",\"coins\":1,\"usd_price_hint\":0.99}]";

        List<CoinOffer.Tier> tiers = CoinOffer.tiers(odd);

        assertEquals(1, tiers.size());
        assertEquals("small", tiers.get(0).getSuffix());
    }

    @Test
    void malformedCatalogYieldsNoTiersRatherThanThrowing() {
        assertTrue(CoinOffer.tiers("not json at all: [[[").isEmpty());
        assertTrue(CoinOffer.tiers("{\"not\":\"an array\"}").isEmpty());
    }

    // ---- tierSuffix ----

    @Test
    void tierSuffixReadsTheSegmentAfterTheAicoinInfix() {
        assertEquals("large", CoinOffer.tierSuffix("com.tarasmaslov.learnit.aicoin.large"));
        assertEquals(null, CoinOffer.tierSuffix("com.tarasmaslov.learnit.large"));
        assertEquals(null, CoinOffer.tierSuffix("com.tarasmaslov.learnit.aicoin."));
        assertEquals(null, CoinOffer.tierSuffix("com.tarasmaslov.learnit.aicoin.large.extra"));
    }

    // ---- resolveByPrice ----

    /**
     * The worked example from the design decision: 350 coins at a $0.0086 signal raw-price to
     * $6.45, which must land on $9.99. Rounding to the *nearest* of the catalog's four points
     * would pick $2.99 and sell those coins for less than half their worth — see the class doc on
     * why this deliberately does not share {@link AppStorePriceRounding#roundToNearestTier}'s
     * direction. (That method rounds against its own, much denser tier ladder, which is a
     * different ladder from the four price points the catalog actually sells at.)
     */
    @Test
    void resolveRoundsUpToTheCheapestPointThatCoversTheRawPrice() {
        double rawPrice = AppStorePriceRounding.rawPrice(350, 0.0086);
        assertTrue(rawPrice > 2.99 && rawPrice < 9.99, "raw price should sit between two points: " + rawPrice);
        assertTrue(rawPrice - 2.99 < 9.99 - rawPrice,
                "the cheaper point is the nearer one — the choice this must not make: " + rawPrice);

        CoinOffer.Resolved resolved = CoinOffer.resolveByPrice(CATALOG, 350, 0.0086).orElseThrow();

        assertEquals(350, resolved.getCoins());
        assertEquals("large", resolved.getTier());
        assertEquals(9.99, resolved.getUsdPrice());
    }

    @Test
    void resolveKeepsTheCoinAmountAskedForNotTheProductsOwn() {
        CoinOffer.Resolved resolved = CoinOffer.resolveByPrice(CATALOG, 350, 0.0086).orElseThrow();

        // The .large products' catalog entries say 1000 coins; the offer sells 350 at that price.
        assertEquals(350, resolved.getCoins());
        assertTrue(resolved.sells("com.tarasmaslov.learnit.aicoin.large"));
        assertFalse(resolved.sells("com.tarasmaslov.learnit.aicoin.small"));
    }

    @Test
    void aPriceLandingExactlyOnAPointStaysOnIt() {
        // Choose coins so the raw price is exactly 2.99.
        double priceUsd = 2.99 / (100 * (1 + AppStorePriceRounding.FEE_MARGIN) / (1 - AppStorePriceRounding.APPLE_CUT));

        CoinOffer.Resolved resolved = CoinOffer.resolveByPrice(CATALOG, 100, priceUsd).orElseThrow();

        assertEquals("medium", resolved.getTier());
    }

    @Test
    void anAmountNoPointCanCoverIsRefusedRatherThanClampedToTheTop() {
        // 100,000 coins at a healthy signal prices far above the $44.99 ceiling.
        assertTrue(CoinOffer.resolveByPrice(CATALOG, 100_000, 0.0086).isEmpty());
    }

    @Test
    void aZeroPriceSignalStillResolvesButOnlyToTheCheapestPoint() {
        // Guarding against this is CoinOfferHandler's job (MIN_WEIGHTED_EVENTS) — the math itself
        // has no opinion, and this test pins that division of responsibility.
        assertEquals("small", CoinOffer.resolveByPrice(CATALOG, 5000, 0.0).orElseThrow().getTier());
    }

    // ---- resolveAtPrice ----

    @Test
    void resolveAtPriceTakesTheNamedPointExactly() {
        CoinOffer.Resolved resolved = CoinOffer.resolveAtPrice(CATALOG, 350, 9.99).orElseThrow();

        assertEquals(9.99, resolved.getUsdPrice());
        assertEquals("large", resolved.getTier());
        assertEquals(350, resolved.getCoins());
    }

    @Test
    void resolveAtPriceRoundsUpWhenTheNamedPriceIsntAPoint() {
        assertEquals("large", CoinOffer.resolveAtPrice(CATALOG, 350, 5.00).orElseThrow().getTier());
    }

    @Test
    void resolveAtPriceAboveEveryPointIsRefused() {
        assertTrue(CoinOffer.resolveAtPrice(CATALOG, 350, 500.0).isEmpty());
    }

    // ---- toJson / parse ----

    @Test
    void toJsonRendersTheStoredShape() {
        CoinOffer.Resolved resolved = CoinOffer.resolveByPrice(CATALOG, 350, 0.0086).orElseThrow();

        assertEquals("{\"coins\":350,\"tier\":\"large\",\"usd_price\":9.99,\"product_ids\":["
                + "\"com.tarasmaslov.infiniteairadio.aicoin.large\","
                + "\"com.tarasmaslov.alllanguageslearner.aicoin.large\","
                + "\"com.tarasmaslov.learnit.aicoin.large\"],\"set_at\":1700000000000}",
                CoinOffer.toJson(resolved, 1_700_000_000_000L));
    }

    @Test
    void parseRoundTripsWhatToJsonWrote() {
        CoinOffer.Resolved original = CoinOffer.resolveByPrice(CATALOG, 350, 0.0086).orElseThrow();

        CoinOffer.Resolved parsed = CoinOffer.parse(CoinOffer.toJson(original, 1L)).orElseThrow();

        assertEquals(350, parsed.getCoins());
        assertEquals("large", parsed.getTier());
        assertEquals(9.99, parsed.getUsdPrice());
        assertEquals(original.getProductIds(), parsed.getProductIds());
        assertTrue(parsed.sells("com.tarasmaslov.learnit.aicoin.large"));
    }

    /** The unset-offer sentinel and any garbage must read back as "no offer", never as a zero-coin one. */
    @Test
    void parseRejectsEmptyMalformedAndNonPositiveOffers() {
        assertTrue(CoinOffer.parse("").isEmpty());
        assertTrue(CoinOffer.parse(null).isEmpty());
        assertTrue(CoinOffer.parse("[[[not json").isEmpty());
        assertTrue(CoinOffer.parse("[]").isEmpty());
        assertTrue(CoinOffer.parse("{\"coins\":0,\"product_ids\":[]}").isEmpty());
        assertTrue(CoinOffer.parse("{\"coins\":-5,\"product_ids\":[]}").isEmpty());
        assertTrue(CoinOffer.parse("{\"coins\":1.5,\"product_ids\":[]}").isEmpty());
        assertTrue(CoinOffer.parse("{\"product_ids\":[]}").isEmpty());
    }

    @Test
    void anOfferWithNoProductsSellsNothing() {
        Optional<CoinOffer.Resolved> parsed = CoinOffer.parse("{\"coins\":10,\"product_ids\":[]}");

        assertTrue(parsed.isPresent());
        assertFalse(parsed.get().sells("com.tarasmaslov.learnit.aicoin.large"));
    }
}
