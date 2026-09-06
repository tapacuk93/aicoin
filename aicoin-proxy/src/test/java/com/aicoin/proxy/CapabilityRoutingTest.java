package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Which provider a consolidated endpoint would actually call: the ratings filtered through what
 * this deployment holds keys for, what its adapters can speak, and what is answering.
 */
class CapabilityRoutingTest {

    private static ProxyConfig configWith(String... envPairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i + 1 < envPairs.length; i += 2) {
            env.put(envPairs[i], envPairs[i + 1]);
        }
        return ProxyConfig.load(env);
    }

    @Test
    void onlyTheThreeCapabilitiesAreEndpoints() {
        assertEquals(Optional.of(Capability.TEXT), Capability.fromPath("/text"));
        assertEquals(Optional.of(Capability.IMAGE), Capability.fromPath("/image"));
        assertEquals(Optional.of(Capability.AUDIO), Capability.fromPath("/audio"));
        assertEquals(Optional.empty(), Capability.fromPath("/video"));
        assertEquals(Optional.empty(), Capability.fromPath("/v1/chat/completions"));
        assertEquals(Optional.empty(), Capability.fromPath("/"));
        assertEquals(Optional.empty(), Capability.fromPath(null));
    }

    @Test
    void aProviderWithNoKeyIsNeverRoutedToHoweverWellRated() {
        // Nothing configured at all: the ratings are unchanged, and every one of them is moot.
        assertTrue(CapabilityHandler.rank(configWith(), null, Capability.TEXT, "code").isEmpty());
        assertTrue(CapabilityHandler.rank(configWith(), null, Capability.IMAGE, "creative").isEmpty());
    }

    @Test
    void theBestRatedConfiguredProviderIsFirst() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_ANTHROPIC_APIKEY", "a",
                "AICOIN_PROXY_MISTRAL_APIKEY", "m");
        // Anthropic is rated 5 at code, Mistral 3 — so Mistral is the fallback, not the choice.
        assertEquals(List.of("anthropic", "mistral"),
                CapabilityHandler.rank(config, null, Capability.TEXT, "code"));
    }

    @Test
    void aSubjectMovesTheRankingNotJustTheCapability() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_ANTHROPIC_APIKEY", "a",
                "AICOIN_PROXY_GOOGLE_APIKEY", "g",
                "AICOIN_PROXY_KIMI_APIKEY", "k");
        assertEquals("anthropic", CapabilityHandler.rank(config, null, Capability.TEXT, "writing").get(0));
        assertEquals("google", CapabilityHandler.rank(config, null, Capability.TEXT, "science").get(0));
        // Google and Kimi are both rated 5 at translation and both outrank Anthropic there, which
        // is 5 at text in general and says nothing about translating.
        List<String> translation = CapabilityHandler.rank(config, null, Capability.TEXT, "translation");
        assertEquals(List.of("google", "kimi", "anthropic"), translation);
    }

    @Test
    void ratingsCanBeCorrectedWithoutARelease() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_ANTHROPIC_APIKEY", "a",
                "AICOIN_PROXY_MISTRAL_APIKEY", "m",
                "AICOIN_PROXY_SKILL_MISTRAL_CODE", "5",
                "AICOIN_PROXY_SKILL_ANTHROPIC_CODE", "1");
        assertEquals("mistral", CapabilityHandler.rank(config, null, Capability.TEXT, "code").get(0));
    }

    @Test
    void aProviderCanBeTakenOutOfRotationByRatingItZero() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_OPENAI_APIKEY", "o",
                "AICOIN_PROXY_STABILITY_APIKEY", "s",
                "AICOIN_PROXY_SKILL_OPENAI_IMAGE", "0");
        assertEquals(List.of("stability"), CapabilityHandler.rank(config, null, Capability.IMAGE, "creative"));
    }

    @Test
    void aTextOnlyProviderIsNeverAskedForAnImage() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_ANTHROPIC_APIKEY", "a",
                "AICOIN_PROXY_KIMI_APIKEY", "k");
        assertTrue(CapabilityHandler.rank(config, null, Capability.IMAGE, "creative").isEmpty());
        assertTrue(CapabilityHandler.rank(config, null, Capability.AUDIO, "creative").isEmpty());
        assertFalse(CapabilityHandler.rank(config, null, Capability.TEXT, "creative").isEmpty());
    }

    @Test
    void aProviderRanksOnlyForTheCapabilitiesItHasAModelAndAnAdapterFor() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_OPENAI_APIKEY", "o",
                "AICOIN_PROXY_STABILITY_APIKEY", "s",
                "AICOIN_PROXY_ELEVENLABS_APIKEY", "e");
        // OpenAI does all three; Stability only draws; ElevenLabs only speaks.
        assertEquals(List.of("openai"), CapabilityHandler.rank(config, null, Capability.TEXT, "general"));
        assertEquals(List.of("openai", "stability"), CapabilityHandler.rank(config, null, Capability.IMAGE, "general"));
        assertEquals(List.of("elevenlabs", "openai"), CapabilityHandler.rank(config, null, Capability.AUDIO, "general"));
    }

    @Test
    void aProviderKnownToBeDownIsSkippedButOneNotYetProbedIsNot() {
        ProxyConfig config = configWith(
                "AICOIN_PROXY_ANTHROPIC_APIKEY", "a",
                "AICOIN_PROXY_MISTRAL_APIKEY", "m");
        ProviderLiveness liveness = new ProviderLiveness(config);
        // Nothing has been probed, so everything reads "unknown" — which is not a reason to refuse
        // to route to a provider that has been fine all week.
        assertEquals(List.of("anthropic", "mistral"),
                CapabilityHandler.rank(config, liveness, Capability.TEXT, "code"));
    }
}
