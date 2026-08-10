package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FreeTargetsTest {

    private static final List<String> OPENAI = List.of("GET /v1/models", "GET /v1/models/*");
    private static final List<String> ANTHROPIC =
            List.of("GET /v1/models", "GET /v1/models/*", "POST /v1/messages/count_tokens");

    @Test
    void exactPathAndMethodMatch() {
        assertTrue(FreeTargets.isFree("GET", "/v1/models", OPENAI));
    }

    @Test
    void wildcardMatchesSubPaths() {
        assertTrue(FreeTargets.isFree("GET", "/v1/models/gpt-4", OPENAI));
        assertTrue(FreeTargets.isFree("GET", "/v1/models/a/b/c", OPENAI));
    }

    @Test
    void inferenceEndpointsAreNotFree() {
        assertFalse(FreeTargets.isFree("POST", "/v1/chat/completions", OPENAI));
        assertFalse(FreeTargets.isFree("POST", "/v1/messages", ANTHROPIC));
        assertFalse(FreeTargets.isFree("POST", "/v1/embeddings", OPENAI));
    }

    @Test
    void methodMustMatch() {
        // Same path, different verb: POSTing to /v1/models isn't the free listing endpoint.
        assertFalse(FreeTargets.isFree("POST", "/v1/models", OPENAI));
        assertTrue(FreeTargets.isFree("POST", "/v1/messages/count_tokens", ANTHROPIC));
        assertFalse(FreeTargets.isFree("GET", "/v1/messages/count_tokens", ANTHROPIC));
    }

    @Test
    void methodComparisonIsCaseInsensitiveButPathIsNot() {
        assertTrue(FreeTargets.isFree("get", "/v1/models", OPENAI));
        assertFalse(FreeTargets.isFree("GET", "/V1/Models", OPENAI));
    }

    @Test
    void patternWithoutMethodMatchesAnyMethod() {
        List<String> patterns = List.of("/v1/voices");
        assertTrue(FreeTargets.isFree("GET", "/v1/voices", patterns));
        assertTrue(FreeTargets.isFree("HEAD", "/v1/voices", patterns));
    }

    @Test
    void starMethodMatchesAnyMethod() {
        List<String> patterns = List.of("* /v1/voices");
        assertTrue(FreeTargets.isFree("GET", "/v1/voices", patterns));
        assertTrue(FreeTargets.isFree("POST", "/v1/voices", patterns));
    }

    @Test
    void midPathWildcardMatchesGoogleCountTokens() {
        List<String> google = List.of("POST /v1beta/models/*:countTokens");
        assertTrue(FreeTargets.isFree("POST", "/v1beta/models/gemini-2.0-flash:countTokens", google));
        assertFalse(FreeTargets.isFree("POST", "/v1beta/models/gemini-2.0-flash:generateContent", google));
    }

    @Test
    void prefixIsNotEnoughWithoutAWildcard() {
        // "/v1/models" must not free "/v1/models-and-more" — the glob is anchored at both ends.
        assertFalse(FreeTargets.isFree("GET", "/v1/modelsomething", OPENAI));
        assertFalse(FreeTargets.isFree("GET", "/v1/models/gpt-4", List.of("GET /v1/models")));
    }

    @Test
    void emptyOrNullInputsAreNeverFree() {
        assertFalse(FreeTargets.isFree("GET", "/v1/models", List.of()));
        assertFalse(FreeTargets.isFree("GET", "/v1/models", null));
        assertFalse(FreeTargets.isFree(null, "/v1/models", OPENAI));
        assertFalse(FreeTargets.isFree("GET", null, OPENAI));
    }

    @Test
    void traversalSegmentsFailClosed() {
        // The upstream would normalize this to /v1/chat/completions — a billed endpoint — so it
        // must not be treated as the free model listing here.
        assertFalse(FreeTargets.isFree("GET", "/v1/models/../chat/completions", OPENAI));
        assertFalse(FreeTargets.isFree("GET", "/v1/models/./x", OPENAI));
        assertFalse(FreeTargets.isFree("GET", "/v1/models/..", OPENAI));
    }

    @Test
    void percentEscapesFailClosed() {
        // %2e%2e and %2f can hide the same traversal past a naive glob.
        assertFalse(FreeTargets.isFree("GET", "/v1/models/%2e%2e/chat/completions", OPENAI));
        assertFalse(FreeTargets.isFree("GET", "/v1/models/%2fx", OPENAI));
    }

    @Test
    void dotsInsideASegmentAreStillFree() {
        // Only whole "." / ".." segments are traversal; a dotted model name is ordinary.
        assertTrue(FreeTargets.isFree("GET", "/v1/models/gemini-2.0-flash", OPENAI));
    }

    @Test
    void parseEnvListSplitsAndTrims() {
        List<String> parsed = FreeTargets.parseEnvList(" GET /v1/models , POST /v1/tokenize ");
        assertTrue(parsed.contains("GET /v1/models"));
        assertTrue(parsed.contains("POST /v1/tokenize"));
        assertTrue(FreeTargets.isFree("POST", "/v1/tokenize", parsed));
    }

    @Test
    void parseEnvListNoneDisablesFreeTargets() {
        assertTrue(FreeTargets.parseEnvList("none").isEmpty());
        assertTrue(FreeTargets.parseEnvList("NONE").isEmpty());
        assertTrue(FreeTargets.parseEnvList("").isEmpty());
        assertTrue(FreeTargets.parseEnvList(null).isEmpty());
    }

    @Test
    void parseYamlListReturnsNullWhenUnset() {
        // null (not empty) is what lets ProxyConfig tell "no freePaths key" (use the defaults)
        // apart from "freePaths: []" (explicitly bill everything).
        org.junit.jupiter.api.Assertions.assertNull(FreeTargets.parseYamlList(null));
        org.junit.jupiter.api.Assertions.assertNull(FreeTargets.parseYamlList("GET /v1/models"));
        org.junit.jupiter.api.Assertions.assertTrue(FreeTargets.parseYamlList(List.of()).isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("GET /v1/models"), FreeTargets.parseYamlList(List.of(" GET /v1/models ")));
    }
}
