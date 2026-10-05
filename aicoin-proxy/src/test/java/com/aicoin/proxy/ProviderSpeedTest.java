package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What speed is allowed to decide, and what it is not.
 *
 * <p>The case this exists for is real and was measured: anthropic and openai are both rated 5 for
 * code, and on the same prompt one answered in 2.5 seconds and the other in 46. Nothing recorded
 * that, so the router picked by canonical order and the caller waited eighteen times longer for an
 * answer the ratings said was no better.
 */
class ProviderSpeedTest {

    private static final int WINDOW = 8;

    @Test
    void saysNothingUntilThereIsEnoughEvidence() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        speed.record("openai", Capability.TEXT, 200, 40_000);
        speed.record("openai", Capability.TEXT, 200, 40_000);
        assertTrue(speed.medianMillis("openai", Capability.TEXT).isEmpty(),
                "two calls is not a measurement");
        speed.record("openai", Capability.TEXT, 200, 40_000);
        assertTrue(speed.medianMillis("openai", Capability.TEXT).isPresent(),
                "three is");
    }

    @Test
    void aProviderNobodyHasCalledIsNotPenalised() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("anthropic", Capability.TEXT, 200, 2_000);
        }
        assertEquals(0.0, speed.penalty("openai", Capability.TEXT, List.of("openai", "anthropic")),
                "unmeasured is not slow");
    }

    @Test
    void failuresDoNotCount() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("mistral", Capability.TEXT, 429, 30);
        }
        assertTrue(speed.medianMillis("mistral", Capability.TEXT).isEmpty(),
                "refusing quickly is not answering quickly");
    }

    @Test
    void oneStallDoesNotDecideAProvidersStanding() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < 6; i++) {
            speed.record("anthropic", Capability.TEXT, 200, 2_000);
        }
        speed.record("anthropic", Capability.TEXT, 200, 90_000);
        assertEquals(2_000, Math.round(speed.medianMillis("anthropic", Capability.TEXT).getAsDouble()),
                "the median ignores the outlier a mean would follow");
    }

    @Test
    void theQuickestProviderPaysNothing() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("anthropic", Capability.TEXT, 200, 2_500);
            speed.record("openai", Capability.TEXT, 200, 46_000);
        }
        List<String> both = List.of("openai", "anthropic");
        assertEquals(0.0, speed.penalty("anthropic", Capability.TEXT, both));
        assertTrue(speed.penalty("openai", Capability.TEXT, both) > 0.5,
                "eighteen times slower should cost most of the maximum");
    }

    @Test
    void slownessNeverCostsAWholeRatingPoint() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("anthropic", Capability.TEXT, 200, 1);
            speed.record("openai", Capability.TEXT, 200, 600_000);
        }
        assertTrue(speed.penalty("openai", Capability.TEXT, List.of("openai", "anthropic")) < 1.0,
                "a fast wrong answer is not a cheaper right one");
    }

    @Test
    void theQuickerOfTwoEqualsIsRankedFirst() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("anthropic", Capability.TEXT, 200, 2_500);
            speed.record("openai", Capability.TEXT, 200, 46_000);
        }
        ProviderSkills skills = ProviderSkills.defaults();
        // Both are rated 5 for code; without a measurement the canonical order decides, and
        // openai comes first in it.
        List<String> blind = skills.rank(Capability.TEXT, "code", null, p -> true);
        List<String> timed = skills.rank(Capability.TEXT, "code", speed, p -> true);
        assertEquals("openai", blind.get(0), "the order this replaces");
        assertEquals("anthropic", timed.get(0), "measured, the quicker equal wins");
    }

    @Test
    void askingForTheQuickestGetsTheQuickest() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("mistral", Capability.TEXT, 200, 100);      // rated 3 for text
            speed.record("anthropic", Capability.TEXT, 200, 2_500);  // rated 5 for code
            speed.record("openai", Capability.TEXT, 200, 46_000);    // also rated 5
        }
        List<String> quickest = ProviderSkills.defaults()
                .fastest(Capability.TEXT, "code", speed, p -> true);
        assertEquals("mistral", quickest.get(0),
                "asked for the quickest, the rating does not get to overrule it");
        assertTrue(quickest.indexOf("anthropic") < quickest.indexOf("openai"),
                "and the rest are in the order they have been answering in");
    }

    @Test
    void theQuickestOfNothingMeasuredIsStillAnOrder() {
        List<String> quickest = ProviderSkills.defaults()
                .fastest(Capability.TEXT, "code", new ProviderSpeed(WINDOW), p -> true);
        assertEquals(ProviderSkills.defaults().rank(Capability.TEXT, "code", p -> true), quickest,
                "with nothing measured this is ordinary ranking, not an empty list");
    }

    @Test
    void speedDoesNotOverruleARealGapInQuality() {
        ProviderSpeed speed = new ProviderSpeed(WINDOW);
        for (int i = 0; i < WINDOW; i++) {
            speed.record("mistral", Capability.TEXT, 200, 100);      // rated 3 for text
            speed.record("anthropic", Capability.TEXT, 200, 46_000); // rated 5 for code
        }
        List<String> timed = ProviderSkills.defaults()
                .rank(Capability.TEXT, "code", speed, p -> true);
        assertFalse(timed.get(0).equals("mistral"),
                "being quick does not make a provider better at the work");
    }
}
