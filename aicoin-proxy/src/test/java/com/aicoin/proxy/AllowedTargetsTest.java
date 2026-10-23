package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The generic route forwards stateless generation, never the stored-object APIs a shared key would
 * share between every wallet. Checked against the lists the proxy actually loads — the bundled
 * application.yaml — so a config edit that reopens a stored-object endpoint fails here.
 */
class AllowedTargetsTest {

    private static final ProxyConfig CONFIG = ProxyConfig.load(Map.of());

    private static boolean allowed(String provider, String method, String path) {
        return AllowedTargets.isAllowed(method, path, CONFIG.getAllowedPaths(provider));
    }

    private static void assertAllowed(String provider, String method, String path) {
        assertTrue(allowed(provider, method, path), provider + " " + method + " " + path + " should be forwarded");
    }

    private static void assertBlocked(String provider, String method, String path) {
        assertFalse(allowed(provider, method, path), provider + " " + method + " " + path + " must be refused");
    }

    @Test
    void whatTheAppsCallIsForwarded() {
        assertAllowed("anthropic", "POST", "/v1/messages");
        assertAllowed("anthropic", "POST", "/v1/messages/count_tokens");
        assertAllowed("anthropic", "GET", "/v1/models");
        assertAllowed("anthropic", "GET", "/v1/models/claude-haiku-4-5");

        assertAllowed("openai", "POST", "/v1/chat/completions");
        assertAllowed("openai", "POST", "/v1/responses");
        assertAllowed("openai", "POST", "/v1/embeddings");
        assertAllowed("openai", "POST", "/v1/audio/speech");
        assertAllowed("openai", "POST", "/v1/images/generations");
        assertAllowed("openai", "GET", "/v1/models");

        assertAllowed("google", "POST", "/v1beta/models/gemini-2.5-flash:generateContent");
        assertAllowed("google", "POST", "/v1beta/models/gemini-2.5-flash:streamGenerateContent");
        assertAllowed("google", "POST", "/v1/models/gemini-2.5-flash:countTokens");
        assertAllowed("google", "POST", "/v1beta/models/text-embedding-004:embedContent");
        assertAllowed("google", "GET", "/v1beta/models");

        assertAllowed("elevenlabs", "POST", "/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL");
        assertAllowed("elevenlabs", "POST", "/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL/stream");
        assertAllowed("elevenlabs", "POST", "/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL/with-timestamps");
        assertAllowed("elevenlabs", "POST", "/v1/music");
        assertAllowed("elevenlabs", "GET", "/v1/voices");
        assertAllowed("elevenlabs", "POST", "/v1/text-to-voice/create-previews");
        assertAllowed("elevenlabs", "POST", "/v1/text-to-voice");
        assertAllowed("elevenlabs", "POST", "/v1/text-to-voice/create-voice-from-preview");
        assertAllowed("elevenlabs", "DELETE", "/v1/voices/abc123");
        assertAllowed("google", "POST", "/v1beta/models/imagen-3.0-generate-002:predict");

        assertAllowed("stability", "POST", "/v2beta/stable-image/generate/core");
        assertAllowed("stability", "POST", "/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image");

        assertAllowed("mistral", "POST", "/v1/chat/completions");
        assertAllowed("cohere", "POST", "/v2/chat");
        assertAllowed("kimi", "POST", "/v1/chat/completions");
    }

    @Test
    void storedObjectApisAreRefusedForEveryProvider() {
        for (String m : List.of("GET", "POST", "DELETE")) {
            assertBlocked("anthropic", m, "/v1/files");
            assertBlocked("anthropic", m, "/v1/files/file_abc");
            assertBlocked("anthropic", m, "/v1/messages/batches");
            assertBlocked("anthropic", m, "/v1/skills");
            assertBlocked("anthropic", m, "/v1/agents");
            assertBlocked("anthropic", m, "/v1/sessions");
        }
        assertBlocked("anthropic", "GET", "/v1/messages/batches/msgbatch_1/results");
        assertBlocked("anthropic", "GET", "/v1/files/file_abc/content");

        assertBlocked("openai", "GET", "/v1/files");
        assertBlocked("openai", "GET", "/v1/files/file-abc/content");
        assertBlocked("openai", "DELETE", "/v1/files/file-abc");
        assertBlocked("openai", "GET", "/v1/responses/resp_abc");
        assertBlocked("openai", "DELETE", "/v1/responses/resp_abc");
        assertBlocked("openai", "GET", "/v1/responses/resp_abc/input_items");
        assertBlocked("openai", "GET", "/v1/chat/completions");
        assertBlocked("openai", "GET", "/v1/chat/completions/chatcmpl-abc/messages");
        assertBlocked("openai", "POST", "/v1/vector_stores");
        assertBlocked("openai", "POST", "/v1/assistants");
        assertBlocked("openai", "POST", "/v1/threads");
        assertBlocked("openai", "POST", "/v1/batches");
        assertBlocked("openai", "POST", "/v1/uploads");
        assertBlocked("openai", "POST", "/v1/fine_tuning/jobs");
        assertBlocked("openai", "POST", "/v1/conversations");
        assertBlocked("openai", "DELETE", "/v1/models/ft:gpt-4o-mini:org:custom:abc");

        assertBlocked("google", "GET", "/v1beta/files");
        assertBlocked("google", "POST", "/upload/v1beta/files");
        assertBlocked("google", "GET", "/v1beta/cachedContents");
        assertBlocked("google", "POST", "/v1beta/tunedModels");
        assertBlocked("google", "POST", "/v1beta/tunedModels/mine:generateContent");
        assertBlocked("google", "POST", "/v1beta/models/gemini-2.5-flash:batchGenerateContent");
        assertBlocked("google", "GET", "/v1beta/batches");
        assertBlocked("google", "POST", "/v1beta/corpora");
        assertBlocked("google", "POST", "/v1beta/fileSearchStores");
        assertBlocked("google", "DELETE", "/v1beta/models/x");

        assertBlocked("mistral", "GET", "/v1/files");
        assertBlocked("mistral", "POST", "/v1/agents");
        assertBlocked("mistral", "POST", "/v1/conversations");
        assertBlocked("mistral", "POST", "/v1/batch/jobs");
        assertBlocked("mistral", "POST", "/v1/fine_tuning/jobs");
        assertBlocked("mistral", "POST", "/v1/libraries");

        assertBlocked("cohere", "GET", "/v1/datasets");
        assertBlocked("cohere", "POST", "/v1/connectors");
        assertBlocked("cohere", "POST", "/v1/embed-jobs");
        assertBlocked("cohere", "POST", "/v1/finetuning/finetuned-models");

        assertBlocked("elevenlabs", "GET", "/v1/history");
        assertBlocked("elevenlabs", "GET", "/v1/history/abc/audio");
        assertBlocked("elevenlabs", "DELETE", "/v1/history/abc");
        assertBlocked("elevenlabs", "POST", "/v1/voices/add");
        assertBlocked("elevenlabs", "POST", "/v1/voices/abc/edit");
        assertBlocked("elevenlabs", "GET", "/v1/user");
        assertBlocked("elevenlabs", "GET", "/v1/voices/abc123");
        assertBlocked("elevenlabs", "DELETE", "/v1/voices/abc/samples/s1");
        assertBlocked("elevenlabs", "POST", "/v1/dubbing");
        assertBlocked("elevenlabs", "GET", "/v1/projects");
        assertBlocked("elevenlabs", "GET", "/v1/convai/agents");
        assertBlocked("elevenlabs", "POST", "/v1/pronunciation-dictionaries/add-from-file");

        assertBlocked("stability", "GET", "/v1/user/account");
        assertBlocked("stability", "GET", "/v2beta/results/abc");

        assertBlocked("kimi", "GET", "/v1/files");
        assertBlocked("kimi", "POST", "/v1/files");
        assertBlocked("kimi", "GET", "/v1/files/abc/content");
    }

    @Test
    void theBundledYamlSaysWhatTheCodeDefaultsSay() {
        for (Map.Entry<String, List<String>> e : AllowedTargets.DEFAULTS.entrySet()) {
            assertEquals(e.getValue(), CONFIG.getAllowedPaths(e.getKey()), e.getKey());
        }
    }

    @Test
    void anUnknownProviderForwardsNothing() {
        assertTrue(CONFIG.getAllowedPaths("nobody").isEmpty());
        assertFalse(AllowedTargets.isAllowed("POST", "/v1/messages", List.of()));
    }

    @Test
    void aSingleStarStaysInsideOneSegment() {
        List<String> p = List.of("POST /v1beta/models/*:generateContent", "GET /v1/models/*");
        assertTrue(AllowedTargets.isAllowed("POST", "/v1beta/models/gemini:generateContent", p));
        assertFalse(AllowedTargets.isAllowed("POST", "/v1beta/models/a/cachedContents/b:generateContent", p));
        assertFalse(AllowedTargets.isAllowed("GET", "/v1/models/a/b", p));
        assertTrue(AllowedTargets.isAllowed("GET", "/v1/anything/at/all", List.of("GET /v1/**")));
        assertFalse(AllowedTargets.isAllowed("DELETE", "/v1/voices/", List.of("DELETE /v1/voices/*")),
                "a star names something: an empty segment is not a voice id");
        assertFalse(AllowedTargets.isAllowed("POST", "/v1beta/models/:generateContent", p));
    }

    @Test
    void methodMustMatchUnlessStar() {
        assertFalse(AllowedTargets.isAllowed("DELETE", "/v1/models/x", List.of("GET /v1/models/*")));
        assertTrue(AllowedTargets.isAllowed("delete", "/v1/models/x", List.of("* /v1/models/*")));
    }

    @Test
    void pathsTheUpstreamWouldRewriteAreRefused() {
        List<String> p = List.of("POST /v1/messages", "GET /v1/models/*", "POST /v1/text-to-speech/*");
        assertFalse(AllowedTargets.isAllowed("GET", "/v1/models/../files", p));
        assertFalse(AllowedTargets.isAllowed("GET", "/v1/models/..", p));
        assertFalse(AllowedTargets.isAllowed("GET", "/v1/models/%2e%2e", p));
        assertFalse(AllowedTargets.isAllowed("POST", "/v1/text-to-speech/x%2F..%2F..%2Fhistory", p));
        assertFalse(AllowedTargets.isAllowed("POST", "//v1/messages", p));
        assertFalse(AllowedTargets.isAllowed("POST", "http://api.anthropic.com/v1/messages", p));
        assertFalse(AllowedTargets.isAllowed("POST", "/v1/messages/", p));
    }

    @Test
    void envOverridesAndNoneBlocksEverything() {
        ProxyConfig c = ProxyConfig.load(Map.of(
                "AICOIN_PROXY_OPENAI_ALLOWEDPATHS", "POST /v1/chat/completions, GET /v1/files",
                "AICOIN_PROXY_ANTHROPIC_ALLOWEDPATHS", "none"));
        assertEquals(List.of("POST /v1/chat/completions", "GET /v1/files"), c.getAllowedPaths("openai"));
        assertTrue(c.getAllowedPaths("anthropic").isEmpty());
    }

    // --- OpenAI POST /v1/responses body rules -------------------------------------------------

    private static AllowedTargets.BodyDecision responses(String body) {
        return AllowedTargets.checkBody("openai", "POST", "/v1/responses",
                body.getBytes(StandardCharsets.UTF_8), null);
    }

    private static String text(AllowedTargets.BodyDecision d) {
        return new String(d.body(), StandardCharsets.UTF_8);
    }

    @Test
    void storeIsForcedOffWhenMissing() {
        AllowedTargets.BodyDecision d = responses("{\"model\":\"gpt-5-nano\",\"input\":\"hi\"}");
        assertFalse(d.isRefused());
        assertEquals("{\"store\":false,\"model\":\"gpt-5-nano\",\"input\":\"hi\"}", text(d));
        Map<?, ?> parsed = (Map<?, ?>) Json.load(text(d));
        assertEquals(Boolean.FALSE, parsed.get("store"));
    }

    @Test
    void storeIsForcedOffInAnEmptyOrIndentedObject() {
        assertEquals("{\"store\":false}", text(responses("{}")));
        assertEquals("{\"store\":false}", text(responses("{ }")).replace(" ", ""));
        String pretty = text(responses("\n  {\n  \"model\": \"x\"\n}"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) Json.load(pretty)).get("store"));
        assertEquals("x", ((Map<?, ?>) Json.load(pretty)).get("model"));
    }

    @Test
    void storeFalseIsForwardedUntouched() {
        byte[] body = "{\"model\":\"x\",\"store\":false,\"temperature\":0.70}".getBytes(StandardCharsets.UTF_8);
        AllowedTargets.BodyDecision d = AllowedTargets.checkBody("openai", "POST", "/v1/responses", body, null);
        assertFalse(d.isRefused());
        assertArrayEquals(body, d.body());
    }

    @Test
    void storeTrueAndStoredStateAreRefused() {
        assertTrue(responses("{\"model\":\"x\",\"store\":true}").isRefused());
        assertTrue(responses("{\"model\":\"x\",\"store\":null}").isRefused());
        assertTrue(responses("{\"model\":\"x\",\"st\\u006fre\":true}").isRefused(), "an escaped key is still the key");
        assertTrue(responses("{\"model\":\"x\",\"store\":false,\"store\":true}").isRefused(), "a repeated key counts by its last value");
        assertTrue(responses("{\"model\":\"x\",\"previous_response_id\":\"resp_abc\"}").isRefused());
        assertTrue(responses("{\"model\":\"x\",\"conversation\":\"conv_abc\"}").isRefused());
        assertTrue(responses("[1]").isRefused());
        assertTrue(responses("not json").isRefused());
        assertTrue(AllowedTargets.checkBody("openai", "POST", "/v1/responses",
                "{}".getBytes(StandardCharsets.UTF_8), "gzip").isRefused());
    }

    @Test
    void otherEndpointsAreForwardedVerbatim() {
        byte[] body = "{\"model\":\"x\",\"store\":true}".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(body, AllowedTargets.checkBody("openai", "POST", "/v1/chat/completions", body, null).body());
        assertArrayEquals(body, AllowedTargets.checkBody("anthropic", "POST", "/v1/responses", body, null).body());
        byte[] binary = new byte[] {(byte) 0xff, 0x00, 0x01};
        assertArrayEquals(binary, AllowedTargets.checkBody("openai", "POST", "/v1/audio/transcriptions", binary, "gzip").body());
    }
}
