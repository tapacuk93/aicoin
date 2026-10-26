package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What makes a consortium call survive its connection and its process: the checkpoint must carry
 * everything a resume needs, and carry it back unchanged — a resumed call whose record differs
 * from the one its panel saw answers a different conversation.
 */
class ConsortiumDurabilityTest {

    private static ProxyConfig config() {
        return ProxyConfig.load(new HashMap<>());
    }

    @Test
    void theRecordSurvivesASnapshotExactly() {
        SharedContext context = new SharedContext("Write 1.3", "background", 400);
        context.addLibraryIndex("1  Origins  (900 words)");
        context.addLibraryRead("Part 1 of the work — read at Kimi's request while drafting", "x".repeat(900));
        context.addDrafts(List.of("anthropic", "kimi"), List.of("draft a", "draft b"));
        context.addAnswer("The answer as it now stands", "merged");
        context.addReviews(1, List.of("kimi"), List.of("too short"), List.of("anthropic"));
        context.addAnswer("The answer as it now stands (revised after round 1)", "revised");

        Object reloaded = Json.load(Json.write(context.snapshot()));
        SharedContext restored = SharedContext.restore((Map<?, ?>) reloaded, 400);

        assertEquals(context.render(), restored.render());
        assertEquals(context.forTurn("Review it."), restored.forTurn("Review it."));
    }

    @Test
    void aSessionCheckpointRestoresToTheSameState() {
        ProxyConfig config = config();
        ConsortiumSession session = new ConsortiumSession(config, null, null, null,
                "a".repeat(64), "chapter-1-3", null, "Write 1.3", "ctx", List.of("anthropic", "kimi"), "anthropic",
                Map.of("anthropic", List.of("claude-opus-5-5"),
                        "kimi", List.of("kimi-k3.1-pro-highspeed", "kimi-k3.1-pro")),
                2, false, false, false, "writing", null, true, 20000, null, UpstreamTimer.NONE, 0, 0, Map.of());
        String json = Json.write(session.snapshot());

        ConsortiumSession restored = ConsortiumSession.restore((Map<?, ?>) Json.load(json),
                List.of("{\"event\":\"start\",\"seq\":1}", "{\"event\":\"turn\",\"seq\":2}"),
                "{\"prompt\":\"Write 1.3\"}", config, null, null, null, null);

        Map<String, Object> again = restored.snapshot();
        Map<?, ?> original = (Map<?, ?>) Json.load(json);
        for (String key : List.of("panel", "editor", "models", "max_rounds", "best", "max_output_tokens",
                "phase", "round", "context", "calls", "coins_charged")) {
            assertEquals(Json.write(original.get(key)), Json.write(again.get(key)), key);
        }
        // Numbering carries on from the stored events, which can run ahead of the checkpoint.
        assertEquals(2, again.get("seq"));
    }

    @Test
    void eventNumbersAreReadBackFromStoredLines() {
        assertEquals(7, ConsortiumSession.seqOf("{\"event\":\"turn\",\"seq\":7,\"stage\":\"draft\"}"));
        assertEquals(0, ConsortiumSession.seqOf("not json"));
    }

    @Test
    void callsAreKeyedByWalletSoOneWalletCannotNameAnothersCall() {
        assertTrue(ConsortiumStore.key("aa", "x").contains(":aa:"));
        assertTrue(!ConsortiumStore.key("aa", "x").equals(ConsortiumStore.key("bb", "x")));
    }
}
