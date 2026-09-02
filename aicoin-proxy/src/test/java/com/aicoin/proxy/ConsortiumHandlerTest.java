package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Who ends up on a panel, and who edits — the two decisions a consortium makes before it spends
 * anything. Both are pure functions of config, so they are pinned here rather than against live
 * providers.
 */
class ConsortiumHandlerTest {

    private static ProxyConfig configWithKeys(String... providers) {
        Map<String, String> env = new HashMap<>();
        for (String provider : providers) {
            env.put("AICOIN_PROXY_" + provider.toUpperCase(java.util.Locale.ROOT) + "_APIKEY", provider + "-key");
        }
        return ProxyConfig.load(env);
    }

    @Test
    void thePanelIsEveryChatProviderThisDeploymentHasAKeyFor() {
        ProxyConfig config = configWithKeys("anthropic", "openai", "kimi");
        assertEquals(List.of("anthropic", "openai", "kimi"), ConsortiumHandler.panel(config, null));
    }

    @Test
    void aProviderWithNoKeyIsNotOnThePanel() {
        // Health reports it as `enabled:false` for the same reason: without a key the proxy cannot
        // call it, and a panelist that cannot be called would just be a guaranteed error per round.
        ProxyConfig config = configWithKeys("anthropic");
        assertEquals(List.of("anthropic"), ConsortiumHandler.panel(config, null));
    }

    @Test
    void nonChatProvidersAreNeverOnThePanelEvenWithAKey() {
        ProxyConfig config = configWithKeys("elevenlabs", "stability", "cohere", "anthropic");
        List<String> panel = ConsortiumHandler.panel(config, null);
        assertEquals(List.of("anthropic"), panel);
        assertFalse(panel.contains("elevenlabs"));
        assertFalse(panel.contains("stability"));
        assertFalse(panel.contains("cohere"));
    }

    @Test
    void aCallerCanNarrowThePanelButNotInventOne() {
        ProxyConfig config = configWithKeys("anthropic", "openai", "kimi");
        assertEquals(List.of("anthropic", "kimi"),
                ConsortiumHandler.panel(config, Set.of("anthropic", "kimi")));
        // Asking for a provider with no key configured doesn't add it.
        assertEquals(List.of("anthropic"), ConsortiumHandler.panel(config, Set.of("anthropic", "google")));
    }

    @Test
    void panelOrderIsStableWhicheverOrderTheCallerAsksIn() {
        // The panel order decides the default editor, so it must not depend on how the request
        // happened to list the providers.
        ProxyConfig config = configWithKeys("anthropic", "openai", "kimi");
        assertEquals(ConsortiumHandler.panel(config, Set.of("kimi", "openai", "anthropic")),
                ConsortiumHandler.panel(config, Set.of("anthropic", "kimi", "openai")));
    }

    @Test
    void theEditorIsAlwaysSomeoneOnThePanel() {
        ProxyConfig config = configWithKeys("anthropic", "openai");
        List<String> panel = ConsortiumHandler.panel(config, null);

        assertEquals("openai", ConsortiumHandler.editor(config, panel, "openai"));
        // A caller naming a provider that isn't on the panel gets the default rather than an
        // editor the proxy has no key to call.
        assertEquals("anthropic", ConsortiumHandler.editor(config, panel, "kimi"));
        assertEquals("anthropic", ConsortiumHandler.editor(config, panel, null));
    }

    @Test
    void aConfiguredEditorWinsOverPanelOrderWhenItIsOnThePanel() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ANTHROPIC_APIKEY", "a");
        env.put("AICOIN_PROXY_KIMI_APIKEY", "k");
        env.put("AICOIN_PROXY_CONSORTIUM_EDITOR", "kimi");
        ProxyConfig config = ProxyConfig.load(env);
        List<String> panel = ConsortiumHandler.panel(config, null);
        assertEquals("kimi", ConsortiumHandler.editor(config, panel, null));
    }

    @Test
    void aContextHeavyCallIsLedByOneModelUnlessTheCallerSaysOtherwise() {
        // The trade-off this encodes: parallel drafting buys useful disagreement when the request
        // is the whole input, and buys N re-readings of the same material — billed N times — once
        // a directory or a document comes with it.
        String big = "x".repeat(9000);
        String small = "x".repeat(100);

        assertTrue(ConsortiumHandler.isLeadMode("auto", big, 8000));
        assertFalse(ConsortiumHandler.isLeadMode("auto", small, 8000));
        assertFalse(ConsortiumHandler.isLeadMode("auto", null, 8000));

        // An explicit choice always wins: a caller who wants four independent answers over a large
        // context can have them, and one who wants a lead over a one-line question can have that.
        assertTrue(ConsortiumHandler.isLeadMode("lead", null, 8000));
        assertFalse(ConsortiumHandler.isLeadMode("panel", big, 8000));
    }

    @Test
    void withNoProvidersConfiguredThereIsNoPanelAndNoEditor() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        assertTrue(ConsortiumHandler.panel(config, null).isEmpty());
        assertNull(ConsortiumHandler.editor(config, List.of(), null));
    }
}
