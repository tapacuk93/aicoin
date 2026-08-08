package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AppStorePriceRounding}'s formula and nearest-tier rounding — the reference
 * implementation {@code scripts/adjust-iap-prices.sh} mirrors in bash for CONTRACT.md's
 * "Automatic price adjustment" job.
 */
class AppStorePriceRoundingTest {

    private static final double EPSILON = 1e-9;

    @Test
    void rawPriceMatchesContractFormula() {
        // coins * price_usd * (1 + feeMargin) / (1 - appleCut), feeMargin=0.50, appleCut=0.30.
        assertEquals(50 * 0.004 * 1.5 / 0.7, AppStorePriceRounding.rawPrice(50, 0.004), EPSILON);
        assertEquals(1000 * 0.006 * 1.5 / 0.7, AppStorePriceRounding.rawPrice(1000, 0.006), EPSILON);
    }

    @Test
    void rawPriceMatchesContractsWorkedExamplesToTheCent() {
        // CONTRACT.md's "Recommended launch packages" table, "Raw formula" column, at the
        // $0.004/call midpoint estimate.
        assertEquals(0.43, round2(AppStorePriceRounding.rawPrice(50, 0.004)));
        assertEquals(1.71, round2(AppStorePriceRounding.rawPrice(200, 0.004)));
        assertEquals(8.57, round2(AppStorePriceRounding.rawPrice(1000, 0.004)));
        assertEquals(42.86, round2(AppStorePriceRounding.rawPrice(5000, 0.004)));
    }

    @Test
    void roundToNearestTierPicksTheClosestTier() {
        assertEquals(0.99, AppStorePriceRounding.roundToNearestTier(0.43), EPSILON);
        assertEquals(2.99, AppStorePriceRounding.roundToNearestTier(3.10), EPSILON);
        assertEquals(44.99, AppStorePriceRounding.roundToNearestTier(42.86), EPSILON);
    }

    @Test
    void roundToNearestTierBreaksTiesTowardTheFirstTierSeen() {
        // Exactly halfway between 4.99 and 5.99 is 5.49; nearer of the two ladder neighbors either way.
        double result = AppStorePriceRounding.roundToNearestTier(5.49);
        assertEquals(0.0, Math.min(Math.abs(result - 4.99), Math.abs(result - 5.99)), EPSILON);
    }

    @Test
    void roundToNearestTierClampsBelowTheLowestTier() {
        assertEquals(0.99, AppStorePriceRounding.roundToNearestTier(0.0), EPSILON);
        assertEquals(0.99, AppStorePriceRounding.roundToNearestTier(-5.0), EPSILON);
    }

    @Test
    void roundToNearestTierClampsAboveTheHighestTier() {
        double[] tiers = AppStorePriceRounding.priceTiers();
        double highest = tiers[tiers.length - 1];
        assertEquals(highest, AppStorePriceRounding.roundToNearestTier(highest + 1000), EPSILON);
    }

    /**
     * {@link #targetPrice} against two of CONTRACT.md's four "Recommended launch packages"
     * examples (Small and XL) — these two happen to already sit closest to the exact launch
     * price shown in that table under principled nearest-tier rounding. The other two rows in
     * that table (Medium: $1.71 raw -> table shows $2.99; Large: $8.57 raw -> table shows $9.99)
     * were chosen by CONTRACT.md's author as clean, memorable launch numbers rather than
     * mechanically derived from a rounding rule — nearest-tier rounding of those exact raw values
     * actually lands one tier lower ($1.99 / $8.99). See the note added to CONTRACT.md's
     * "Recommended launch packages" section and {@link AppStorePriceRounding}'s class javadoc:
     * the *seeded* application.yaml config uses the table's literal numbers directly (unaffected
     * by this function at all), and only the hourly automatic-adjustment job applies this
     * mechanical rule going forward, once real {@code /price} data supersedes the launch estimate.
     */
    @Test
    void targetPriceMatchesContractsExampleTiersWhereTheyAlignWithNearestRounding() {
        assertEquals(0.99, AppStorePriceRounding.targetPrice(50, 0.004), EPSILON);
        assertEquals(44.99, AppStorePriceRounding.targetPrice(5000, 0.004), EPSILON);
    }

    @Test
    void targetPriceOnTheOtherTwoExampleTiersRoundsToTheMechanicallyNearestTierInstead() {
        assertEquals(1.99, AppStorePriceRounding.targetPrice(200, 0.004), EPSILON);
        assertEquals(8.99, AppStorePriceRounding.targetPrice(1000, 0.004), EPSILON);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
