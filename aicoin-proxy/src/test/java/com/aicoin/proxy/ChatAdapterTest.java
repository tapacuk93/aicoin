package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the one place this proxy writes a provider's request instead of forwarding one, per
 * CONTRACT.md's "Consortium" section. The failure these guard against is silent from the outside:
 * a body in the wrong shape comes back 400 from the provider and reads exactly like a consortium
 * whose panelist is down.
 */
class ChatAdapterTest {

    private static String body(String provider, String model) {
        return new String(ChatAdapter.body(provider, model, "SYS", "USER", 1234), StandardCharsets.UTF_8);
    }

    private static Map<?, ?> parse(String json) {
        return (Map<?, ?>) new Yaml().load(json);
    }

    @Test
    void anthropicTakesTheSystemPromptAtTopLevelAndMaxTokens() {
        Map<?, ?> parsed = parse(body("anthropic", "claude-sonnet-5"));
        assertEquals("claude-sonnet-5", parsed.get("model"));
        assertEquals("SYS", parsed.get("system"));
        assertEquals(1234, parsed.get("max_tokens"));
        assertEquals("/v1/messages", ChatAdapter.path("anthropic", "claude-sonnet-5"));
        assertTrue(ChatAdapter.headers("anthropic").stream()
                        .anyMatch(h -> h.getKey().equals("anthropic-version")),
                "the Messages API rejects a request without a version header");
    }

    @Test
    void openAiCapsCompletionTokensByTheirNewerFieldName() {
        // GPT-5 and later reject `max_tokens` on /v1/chat/completions outright, and the consortium
        // defaults to GPT-5 — sending the old field name would 400 every OpenAI turn.
        Map<?, ?> parsed = parse(body("openai", "gpt-5"));
        assertEquals(1234, parsed.get("max_completion_tokens"));
        assertNull(parsed.get("max_tokens"));
        assertEquals("/v1/chat/completions", ChatAdapter.path("openai", "gpt-5"));
    }

    @Test
    void kimiAndMistralKeepTheOriginalMaxTokensField() {
        assertEquals(1234, parse(body("kimi", "kimi-k2.6")).get("max_tokens"));
        assertEquals(1234, parse(body("mistral", "mistral-large-latest")).get("max_tokens"));
    }

    @Test
    void openAiCompatibleBodiesCarryTheSystemPromptAsTheFirstMessage() {
        Map<?, ?> parsed = parse(body("kimi", "kimi-k2.6"));
        List<?> messages = (List<?>) parsed.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", ((Map<?, ?>) messages.get(0)).get("role"));
        assertEquals("SYS", ((Map<?, ?>) messages.get(0)).get("content"));
        assertEquals("user", ((Map<?, ?>) messages.get(1)).get("role"));
        assertEquals("USER", ((Map<?, ?>) messages.get(1)).get("content"));
    }

    @Test
    void geminiNamesTheModelInThePathAndWrapsTextInParts() {
        assertEquals("/v1beta/models/gemini-3.5-flash:generateContent",
                ChatAdapter.path("google", "gemini-3.5-flash"));
        Map<?, ?> parsed = parse(body("google", "gemini-3.5-flash"));
        assertNull(parsed.get("model"), "Gemini takes the model in the path, not the body");
        Map<?, ?> generationConfig = (Map<?, ?>) parsed.get("generationConfig");
        assertEquals(1234, generationConfig.get("maxOutputTokens"));
    }

    @Test
    void promptsWithQuotesAndNewlinesSurviveIntoAValidBody() {
        // A consortium pastes drafts and reviews — model output, with every character in it —
        // straight into the next request body.
        String awkward = "He said \"hi\"\nand a backslash \\ and a tab\there";
        Map<?, ?> parsed = parse(new String(
                ChatAdapter.body("anthropic", "m", "SYS", awkward, 10), StandardCharsets.UTF_8));
        List<?> messages = (List<?>) parsed.get("messages");
        assertEquals(awkward, ((Map<?, ?>) messages.get(0)).get("content"));
    }

    @Test
    void readsTheAssistantTextOutOfEachProvidersOwnShape() {
        assertEquals("hello", ChatAdapter.text("anthropic",
                "{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));
        assertEquals("hello", ChatAdapter.text("openai",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}"));
        assertEquals("hello", ChatAdapter.text("kimi",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"}}]}"));
        assertEquals("hello", ChatAdapter.text("google",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}"));
    }

    @Test
    void geminiThoughtPartsAreNotTheAnswer() {
        // A thinking model returns its reasoning as a part flagged `thought`. Pasting that into
        // the merged answer would publish the model's scratchpad as the panel's output.
        assertEquals("the answer", ChatAdapter.text("google",
                "{\"candidates\":[{\"content\":{\"parts\":["
                        + "{\"text\":\"first I should\",\"thought\":true},{\"text\":\"the answer\"}]}}]}"));
    }

    @Test
    void aResponseWithNoTextIsNullRatherThanEmpty() {
        // A model that spends its whole output cap thinking answers 2xx with no text part at all;
        // the round has to treat that as a panelist that didn't answer.
        assertNull(ChatAdapter.text("google", "{\"candidates\":[{\"content\":{\"parts\":[]}}]}"));
        assertNull(ChatAdapter.text("openai", "{\"choices\":[]}"));
        assertNull(ChatAdapter.text("anthropic", "{\"content\":[{\"type\":\"text\",\"text\":\"   \"}]}"));
        assertNull(ChatAdapter.text("openai", "not json at all"));
    }

    @Test
    void onlyProvidersWithAKnownChatShapeCanBeOnAPanel() {
        assertTrue(ChatAdapter.supports("anthropic"));
        assertTrue(ChatAdapter.supports("kimi"));
        // Neither is a chat API: ElevenLabs synthesises speech, Stability generates images.
        assertFalse(ChatAdapter.supports("elevenlabs"));
        assertFalse(ChatAdapter.supports("stability"));
        // Cohere is a chat API, but of a shape this proxy has no key to exercise.
        assertFalse(ChatAdapter.supports("cohere"));
    }
}
