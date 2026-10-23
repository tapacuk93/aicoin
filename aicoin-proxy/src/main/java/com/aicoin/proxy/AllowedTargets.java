package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which provider endpoints the generic {@code X-AI} route will forward at all.
 *
 * <p>Every wallet holder's call goes out under the proxy's <em>one</em> key per provider, so to
 * the provider they are all the same customer. Anything a provider stores on behalf of its
 * customer — an uploaded file, a batch and its results, an OpenAI response kept for 30 days, a
 * vector store, a cached Gemini context, an ElevenLabs generation in the account's history, a
 * designed voice — is therefore stored for <em>everyone</em>: whoever can name the endpoint can
 * list it, read it and delete it. Before this list existed the route forwarded any method and any
 * path, which made {@code GET /v1/files} with {@code X-AI: anthropic} a way to read other people's
 * uploads.
 *
 * <p>So the route forwards only stateless generation and listing endpoints, named per provider in
 * {@code providers.<name>.allowedPaths}, and answers everything else {@code 403} before any coin
 * is held or any provider is contacted. It is an allowlist rather than a blocklist because
 * providers add stored-object APIs faster than anyone would notice them here, and a new endpoint
 * that is wrongly refused is a bug report, while one that is wrongly forwarded is a leak.
 *
 * <p><b>Pattern syntax</b>: an optional HTTP method ({@code *} for any), a space, then a path
 * glob. Unlike {@link FreeTargets}, {@code *} here stops at {@code /} — it is one path segment or
 * a non-empty part of one, so {@code POST /v1beta/models/*:generateContent} cannot be stretched over a
 * different resource — and {@code **} matches any run of characters including {@code /}. Paths
 * that are not already normalized (a percent-escape, a {@code .} or {@code ..} segment, an empty
 * segment) are refused outright, because the upstream would route them somewhere other than where
 * the pattern looked.
 *
 * <p>Some allowed endpoints store by default. OpenAI's {@code POST /v1/responses} keeps every
 * response unless told {@code "store": false}, and retrieves it for anyone who has the id; {@link
 * #checkBody} forces that off and refuses the parameters that only work against stored state.
 */
public final class AllowedTargets {

    private AllowedTargets() {
    }

    /**
     * The bundled lists, which {@code application.yaml} repeats so they can be read and changed in
     * one place. Each is what the apps in this repository's family actually call (see CONTRACT.md's
     * "Routing" section), plus that provider's other plainly stateless generation endpoints.
     */
    static final Map<String, List<String>> DEFAULTS = defaults();

    private static Map<String, List<String>> defaults() {
        Map<String, List<String>> d = new LinkedHashMap<>();
        d.put("openai", List.of(
                "POST /v1/chat/completions", "POST /v1/completions", "POST /v1/responses",
                "POST /v1/responses/input_tokens", "POST /v1/embeddings", "POST /v1/moderations",
                "POST /v1/audio/speech", "POST /v1/audio/transcriptions", "POST /v1/audio/translations",
                "POST /v1/images/generations", "POST /v1/images/edits", "POST /v1/images/variations",
                "GET /v1/models", "GET /v1/models/*"));
        d.put("anthropic", List.of(
                "POST /v1/messages", "POST /v1/messages/count_tokens",
                "GET /v1/models", "GET /v1/models/*"));
        d.put("google", List.of(
                "POST /v1/models/*:generateContent", "POST /v1beta/models/*:generateContent",
                "POST /v1/models/*:streamGenerateContent", "POST /v1beta/models/*:streamGenerateContent",
                "POST /v1/models/*:countTokens", "POST /v1beta/models/*:countTokens",
                "POST /v1/models/*:embedContent", "POST /v1beta/models/*:embedContent",
                "POST /v1/models/*:batchEmbedContents", "POST /v1beta/models/*:batchEmbedContents",
                "POST /v1beta/models/*:predict",
                "GET /v1/models", "GET /v1/models/*", "GET /v1beta/models", "GET /v1beta/models/*"));
        d.put("mistral", List.of(
                "POST /v1/chat/completions", "POST /v1/fim/completions", "POST /v1/embeddings",
                "POST /v1/moderations", "POST /v1/chat/moderations",
                "GET /v1/models", "GET /v1/models/*"));
        d.put("cohere", List.of(
                "POST /v1/chat", "POST /v2/chat", "POST /v1/generate", "POST /v1/embed", "POST /v2/embed",
                "POST /v1/rerank", "POST /v2/rerank", "POST /v1/classify",
                "POST /v1/tokenize", "POST /v1/detokenize", "POST /v1/check-api-key",
                "GET /v1/models", "GET /v1/models/*"));
        d.put("elevenlabs", List.of(
                "POST /v1/text-to-speech/*", "POST /v1/text-to-speech/*/stream",
                "POST /v1/text-to-speech/*/with-timestamps", "POST /v1/text-to-speech/*/stream/with-timestamps",
                "POST /v1/sound-generation", "POST /v1/music", "POST /v1/music/stream",
                // Voice design, which the radio and the learner both use. Voices are stored
                // objects, so these are scoped to the wallet that made each one: see VoiceOwnership.
                "POST /v1/text-to-voice/create-previews", "POST /v1/text-to-voice",
                "POST /v1/text-to-voice/create-voice-from-preview", "DELETE /v1/voices/*",
                "GET /v1/voices", "GET /v1/models"));
        d.put("stability", List.of(
                "POST /v1/generation/*/text-to-image", "POST /v1/generation/*/image-to-image",
                "POST /v2beta/stable-image/generate/core", "POST /v2beta/stable-image/generate/sd3",
                "POST /v2beta/stable-image/generate/ultra",
                "GET /v1/engines/list"));
        d.put("kimi", List.of(
                "POST /v1/chat/completions", "POST /v1/tokenizers/estimate-token-count",
                "GET /v1/models", "GET /v1/models/*"));
        return Map.copyOf(d);
    }

    /** @return the bundled list for {@code provider}, empty (nothing forwarded) for one it does not know. */
    static List<String> defaultsFor(String provider) {
        return DEFAULTS.getOrDefault(provider, List.of());
    }

    /**
     * @param method   HTTP method of the inbound request
     * @param path     request path with no query string
     * @param patterns the provider's {@code allowedPaths}
     * @return true when the generic route may forward this request to the provider
     */
    public static boolean isAllowed(String method, String path, List<String> patterns) {
        if (method == null || path == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        if (!isNormalized(path)) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(method, path, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts with {@code /}, no percent-escapes, no {@code .}/{@code ..} segments and no empty
     * segments ({@code //}): the form an upstream routes on without rewriting it first.
     */
    private static boolean isNormalized(String path) {
        if (!path.startsWith("/") || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        String[] segments = path.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String s = segments[i];
            if (".".equals(s) || "..".equals(s)) {
                return false;
            }
            if (s.isEmpty() && i < segments.length - 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String method, String path, String pattern) {
        if (pattern == null) {
            return false;
        }
        String trimmed = pattern.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String glob = trimmed;
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            String patternMethod = trimmed.substring(0, space).trim();
            glob = trimmed.substring(space + 1).trim();
            if (!"*".equals(patternMethod) && !patternMethod.equalsIgnoreCase(method)) {
                return false;
            }
        }
        return globMatches(glob, 0, path, 0);
    }

    /** {@code *} is any run without {@code /}; {@code **} is any run at all. Patterns are short, so plain recursion is fine. */
    private static boolean globMatches(String glob, int g, String value, int v) {
        while (g < glob.length()) {
            char c = glob.charAt(g);
            if (c == '*') {
                boolean deep = g + 1 < glob.length() && glob.charAt(g + 1) == '*';
                int next = deep ? g + 2 : g + 1;
                // A single star is at least one character: "/v1/voices/*" must not match
                // "/v1/voices/", which names no voice at all.
                for (int end = deep ? v : v + 1; end <= value.length(); end++) {
                    if (!deep && value.charAt(end - 1) == '/') {
                        return false;
                    }
                    if (globMatches(glob, next, value, end)) {
                        return true;
                    }
                }
                return false;
            }
            if (v >= value.length() || value.charAt(v) != c) {
                return false;
            }
            g++;
            v++;
        }
        return v == value.length();
    }

    /** What {@link #checkBody} decided: forward {@code body} (possibly rewritten), or refuse with {@code error}. */
    public static final class BodyDecision {
        private final byte[] body;
        private final String error;

        private BodyDecision(byte[] body, String error) {
            this.body = body;
            this.error = error;
        }

        static BodyDecision forward(byte[] body) {
            return new BodyDecision(body, null);
        }

        static BodyDecision refuse(String error) {
            return new BodyDecision(null, error);
        }

        public boolean isRefused() {
            return error != null;
        }

        public byte[] body() {
            return body;
        }

        public String error() {
            return error;
        }
    }

    /**
     * Applies the per-endpoint body rules to an allowed request. Today that is OpenAI's {@code POST
     * /v1/responses}, which stores the response — readable by id to anyone holding this proxy's key
     * — unless the request says {@code "store": false}. The proxy adds that when it is missing,
     * refuses {@code "store": true}, and refuses {@code previous_response_id} and {@code
     * conversation}, which only work by reading back state stored under the shared key. Every
     * other request is forwarded with its body untouched.
     *
     * <p>{@code store} is added as text right after the opening brace rather than by re-serializing
     * the document, so nothing else about the caller's JSON — number formatting, key order,
     * escapes — is changed on the way through.
     *
     * @param contentEncoding the request's {@code Content-Encoding}, if any: an encoded body cannot
     *                        be inspected, so it is refused on an endpoint that needs inspecting
     */
    public static BodyDecision checkBody(String provider, String method, String path, byte[] body,
                                         String contentEncoding) {
        if (!("openai".equals(provider) && "POST".equalsIgnoreCase(method) && "/v1/responses".equals(path))) {
            return BodyDecision.forward(body);
        }
        if (contentEncoding != null && !contentEncoding.isBlank()
                && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
            return BodyDecision.refuse("send POST /v1/responses uncompressed: the proxy must read it to keep it from being stored");
        }
        String text = new String(body, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = Json.load(text);
        } catch (IllegalArgumentException e) {
            return BodyDecision.refuse("request body is not valid JSON");
        }
        if (!(parsed instanceof Map)) {
            return BodyDecision.refuse("request body must be a JSON object");
        }
        Map<?, ?> json = (Map<?, ?>) parsed;
        for (String stateful : List.of("previous_response_id", "conversation")) {
            if (json.get(stateful) != null) {
                return BodyDecision.refuse("\"" + stateful + "\" is not available through the AICoin proxy:"
                        + " it reads responses stored under a key every wallet shares. Send the earlier turns in \"input\" instead");
            }
        }
        if (json.containsKey("store")) {
            if (Boolean.FALSE.equals(json.get("store"))) {
                return BodyDecision.forward(body);
            }
            return BodyDecision.refuse("\"store\" must be false through the AICoin proxy:"
                    + " a stored response is readable by every wallet, since they all share one provider key");
        }
        int brace = text.indexOf('{');
        int after = brace + 1;
        int next = after;
        while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
            next++;
        }
        boolean empty = next < text.length() && text.charAt(next) == '}';
        String rewritten = text.substring(0, after) + "\"store\":false" + (empty ? "" : ",") + text.substring(after);
        return BodyDecision.forward(rewritten.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses {@code AICOIN_PROXY_<PROVIDER>_ALLOWEDPATHS}: comma-separated; {@code none} forwards nothing. */
    static List<String> parseEnvList(String value) {
        return FreeTargets.parseEnvList(value);
    }

    /** Normalizes a YAML-parsed {@code allowedPaths} value; null when the key is absent. */
    static List<String> parseYamlList(Object raw) {
        return FreeTargets.parseYamlList(raw);
    }
}
