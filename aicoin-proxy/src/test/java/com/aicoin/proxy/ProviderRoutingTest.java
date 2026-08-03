package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * X-AI header -> provider resolution, per CONTRACT.md's "Routing" section.
 */
class ProviderRoutingTest {

    @Test
    void resolvesKnownProvider() {
        Optional<String> r = ProviderRouting.resolve("openai");
        assertTrue(r.isPresent());
        assertEquals("openai", r.get());
    }

    @Test
    void resolutionIsCaseInsensitive() {
        Optional<String> r = ProviderRouting.resolve("OpenAI");
        assertTrue(r.isPresent());
        assertEquals("openai", r.get());
    }

    @Test
    void resolutionTrimsWhitespace() {
        Optional<String> r = ProviderRouting.resolve("  anthropic  ");
        assertTrue(r.isPresent());
        assertEquals("anthropic", r.get());
    }

    @Test
    void missingHeaderIsNotResolved() {
        assertFalse(ProviderRouting.resolve(null).isPresent());
    }

    @Test
    void emptyHeaderIsNotResolved() {
        assertFalse(ProviderRouting.resolve("").isPresent());
        assertFalse(ProviderRouting.resolve("   ").isPresent());
    }

    @Test
    void unknownProviderIsNotResolved() {
        assertFalse(ProviderRouting.resolve("notaprovider").isPresent());
    }

    @Test
    void allFiveKnownProvidersResolve() {
        for (String p : new String[] {"openai", "anthropic", "google", "mistral", "cohere"}) {
            Optional<String> r = ProviderRouting.resolve(p);
            assertTrue(r.isPresent(), p + " should resolve");
            assertEquals(p, r.get());
        }
    }
}
