package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** `skip` leaves named providers off the panel, in any case, keeping the panel's order. */
class ConsortiumSkipTest {

    @Test
    void skippedProvidersLeaveThePanelInOrder() {
        Set<String> skip = ConsortiumHandler.skipped(List.of(" Kimi ", "mistral", 3));
        assertEquals(Set.of("kimi", "mistral"), skip);
        assertEquals(List.of("anthropic", "openai", "google"),
                ConsortiumHandler.withoutSkipped(List.of("anthropic", "openai", "kimi", "google"), skip));
    }

    @Test
    void anythingButAListSkipsNoOne() {
        assertTrue(ConsortiumHandler.skipped("kimi").isEmpty());
        assertTrue(ConsortiumHandler.skipped(null).isEmpty());
    }

    @Test
    void skippingEveryoneLeavesNoOne() {
        assertTrue(ConsortiumHandler.withoutSkipped(List.of("kimi"), Set.of("kimi")).isEmpty());
    }
}
