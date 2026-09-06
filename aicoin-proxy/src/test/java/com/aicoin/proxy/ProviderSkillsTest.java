package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How a rating table becomes a routing decision. */
class ProviderSkillsTest {

    private static final ProviderSkills SKILLS = ProviderSkills.defaults();

    @Test
    void aCapabilityRatingOfZeroMeansNeverRoute() {
        // Anthropic writes no images and ElevenLabs writes no prose, however good either is at
        // what it does do.
        assertEquals(0, SKILLS.capabilityRating("anthropic", Capability.IMAGE));
        assertEquals(0, SKILLS.capabilityRating("elevenlabs", Capability.TEXT));
        assertFalse(SKILLS.rank(Capability.IMAGE, "creative", null).contains("anthropic"));
        assertFalse(SKILLS.rank(Capability.TEXT, "writing", null).contains("elevenlabs"));
    }

    @Test
    void aSubjectRatingOverridesTheCapabilityRatingForThatSubject() {
        // Google is rated 4 at text in general and 5 at science in particular.
        assertEquals(4, SKILLS.capabilityRating("google", Capability.TEXT));
        assertEquals(5, SKILLS.score("google", Capability.TEXT, "science"));
        assertEquals("google", SKILLS.rank(Capability.TEXT, "science", null).get(0));
    }

    @Test
    void noRatingForASubjectMeansNoOpinionRatherThanABadScore() {
        // Anthropic has no "math" entry, so it keeps its text rating there rather than dropping to
        // zero and vanishing from the ranking.
        assertEquals(SKILLS.capabilityRating("anthropic", Capability.TEXT),
                SKILLS.score("anthropic", Capability.TEXT, "math"));
        assertTrue(SKILLS.rank(Capability.TEXT, "math", null).contains("anthropic"));
    }

    @Test
    void aSubjectRatingCannotResurrectAProviderThatCannotDoTheCapability() {
        // Stability is rated 5 at creative work — that must not put it in the running for prose.
        assertEquals(5, SKILLS.score("stability", Capability.IMAGE, "creative"));
        assertEquals(0, SKILLS.score("stability", Capability.TEXT, "creative"));
    }

    @Test
    void tiesKeepTheCanonicalProviderOrderSoRoutingIsReproducible() {
        ProviderSkills flat = new ProviderSkills(
                Map.of("openai", Map.of("text", 4), "anthropic", Map.of("text", 4), "kimi", Map.of("text", 4)),
                Map.of());
        List<String> ranked = flat.rank(Capability.TEXT, "general", null);
        assertEquals(List.of("openai", "anthropic", "kimi"), ranked);
        for (int i = 0; i < 20; i++) {
            assertEquals(ranked, flat.rank(Capability.TEXT, "general", null));
        }
    }

    @Test
    void unavailableProvidersAreNotRankedHoweverWellRated() {
        List<String> ranked = SKILLS.rank(Capability.TEXT, "code", provider -> !provider.equals("anthropic"));
        assertFalse(ranked.contains("anthropic"), "a provider that cannot be called is not a candidate");
        assertFalse(ranked.isEmpty());
    }

    @Test
    void anExplicitSubjectRatingOutranksTheSameScoreReachedByFallback() {
        // Anthropic is 5 at text and says nothing about science; Google is 5 at science
        // specifically. Same number, but one of them is an opinion about the question asked.
        assertEquals(5, SKILLS.score("anthropic", Capability.TEXT, "science"));
        assertEquals(5, SKILLS.score("google", Capability.TEXT, "science"));
        assertFalse(SKILLS.hasSubjectRating("anthropic", Capability.TEXT, "science"));
        assertTrue(SKILLS.hasSubjectRating("google", Capability.TEXT, "science"));
        assertEquals("google", SKILLS.rank(Capability.TEXT, "science", null).get(0));
    }

    @Test
    void aTextSubjectRatingDoesNotDecideWhoSpeaksOrDraws() {
        // OpenAI is rated 5 at code and 4 at speech; ElevenLabs is 5 at speech and says nothing
        // about code. Reading a sentence about code aloud is a speech job, so ElevenLabs takes it
        // — being good at writing code is not a qualification for narrating it.
        assertEquals(0, SKILLS.score("elevenlabs", Capability.TEXT, "code"));
        assertEquals(4, SKILLS.score("openai", Capability.AUDIO, "code"));
        assertEquals(5, SKILLS.score("elevenlabs", Capability.AUDIO, "code"));
        assertEquals("elevenlabs", SKILLS.rank(Capability.AUDIO, "code", null).get(0));
        assertEquals("openai", SKILLS.rank(Capability.IMAGE, "code", null).get(0));
    }

    @Test
    void aMediaSubjectRatingIsWrittenUnderItsCapability() {
        assertEquals("code", ProviderSkills.key(Capability.TEXT, "code"));
        assertEquals("image-creative", ProviderSkills.key(Capability.IMAGE, "creative"));
        // An illustration is what Stability is for; a narration is what ElevenLabs is for.
        assertEquals("stability", SKILLS.rank(Capability.IMAGE, "creative", null).get(0));
        assertEquals("elevenlabs", SKILLS.rank(Capability.AUDIO, "creative", null).get(0));
        // ...and neither claim leaks into the other's capability.
        assertEquals(5, SKILLS.capabilityRating("openai", Capability.IMAGE));
        assertEquals(5, SKILLS.score("openai", Capability.IMAGE, "writing"));
    }

    @Test
    void ratingsAreClampedToTheScale() {
        ProviderSkills odd = new ProviderSkills(
                Map.of("openai", Map.of("text", 99), "kimi", Map.of("text", -3)), Map.of());
        assertEquals(ProviderSkills.MAX_RATING, odd.capabilityRating("openai", Capability.TEXT));
        assertEquals(0, odd.capabilityRating("kimi", Capability.TEXT));
    }

    @Test
    void mediaCapabilitiesRankOnlyProvidersThatDoThem() {
        assertEquals(List.of("openai", "stability"), SKILLS.rank(Capability.IMAGE, "general", null));
        // ElevenLabs is rated above OpenAI at speech, which is the whole reason the ranking is by
        // rating rather than by provider order.
        assertEquals(List.of("elevenlabs", "openai"), SKILLS.rank(Capability.AUDIO, "general", null));
        assertEquals("elevenlabs", SKILLS.rank(Capability.AUDIO, "creative", null).get(0));
    }
}
