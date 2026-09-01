package com.aicoin.proxy;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The one place this proxy speaks a provider's chat API rather than forwarding somebody else's
 * request through it, per CONTRACT.md's "Consortium" section.
 *
 * <p>Everywhere else the proxy is deliberately shape-blind: the client sends the provider's own
 * request at the provider's own path and this proxy only injects a key. A consortium call has no
 * such request to copy — the proxy originates every turn itself — so it has to know that Anthropic
 * takes {@code POST /v1/messages} with a top-level {@code system}, that OpenAI-compatible
 * providers take {@code POST /v1/chat/completions} with a system message in the array, and that
 * Gemini names the model in the path and wraps text in {@code contents[].parts[]}.
 *
 * <p>That knowledge is the reason the panel is not simply "every configured provider": a provider
 * whose chat shape isn't here can't be on it. ElevenLabs and Stability are not chat APIs at all,
 * and Cohere's is a shape this proxy has no key to exercise, so none of the three is included.
 */
final class ChatAdapter {

    /**
     * Providers a consortium can hold a turn with, in panel order. Membership means "this class
     * knows the provider's chat shape", not "this deployment can call it" — a provider with no
     * configured apiKey is dropped from the panel separately.
     */
    static final List<String> CHAT_PROVIDERS = List.of("anthropic", "openai", "google", "mistral", "kimi");

    /** Anthropic requires this header on every Messages API call; it is not a config knob. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private ChatAdapter() {
    }

    static boolean supports(String provider) {
        return CHAT_PROVIDERS.contains(provider);
    }

    /** The provider's chat path, including the model when the provider names it there (Gemini). */
    static String path(String provider, String model) {
        if ("anthropic".equals(provider)) {
            return "/v1/messages";
        }
        if ("google".equals(provider)) {
            return "/v1beta/models/" + model + ":generateContent";
        }
        return "/v1/chat/completions";
    }

    /** Headers beyond auth (which {@link AuthInjector} adds) and Host/Content-Length (which Netty does). */
    static List<Map.Entry<String, String>> headers(String provider) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(new AbstractMap.SimpleEntry<>("content-type", "application/json"));
        headers.add(new AbstractMap.SimpleEntry<>("accept", "application/json"));
        // Ask for bytes this proxy can read without inflating them: unlike the forwarding path,
        // nothing here relays the body onward, so there is no reason to accept a compressed one.
        headers.add(new AbstractMap.SimpleEntry<>("accept-encoding", "identity"));
        if ("anthropic".equals(provider)) {
            headers.add(new AbstractMap.SimpleEntry<>("anthropic-version", ANTHROPIC_VERSION));
        }
        return headers;
    }

    /**
     * One single-turn chat request: a system instruction and a user message, capped output, no
     * temperature (the newest models on two of these providers accept only their default, and a
     * panel wants each member's own judgement rather than a sampling setting chosen here).
     */
    static byte[] body(String provider, String model, String system, String user, int maxOutputTokens) {
        StringBuilder json = new StringBuilder();
        if ("anthropic".equals(provider)) {
            json.append("{\"model\":").append(Json.string(model))
                    .append(",\"max_tokens\":").append(maxOutputTokens)
                    .append(",\"system\":").append(Json.string(system))
                    .append(",\"messages\":[{\"role\":\"user\",\"content\":").append(Json.string(user))
                    .append("}]}");
        } else if ("google".equals(provider)) {
            json.append("{\"systemInstruction\":{\"parts\":[{\"text\":").append(Json.string(system))
                    .append("}]},\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":").append(Json.string(user))
                    .append("}]}],\"generationConfig\":{\"maxOutputTokens\":").append(maxOutputTokens).append("}}");
        } else {
            // OpenAI-compatible: OpenAI itself, Mistral, Kimi. OpenAI's newer models reject
            // `max_tokens` on this endpoint and want `max_completion_tokens`; the other two know
            // only `max_tokens`, so the cap is named per provider rather than uniformly.
            String capField = "openai".equals(provider) ? "max_completion_tokens" : "max_tokens";
            json.append("{\"model\":").append(Json.string(model))
                    .append(",\"").append(capField).append("\":").append(maxOutputTokens)
                    .append(",\"messages\":[{\"role\":\"system\",\"content\":").append(Json.string(system))
                    .append("},{\"role\":\"user\",\"content\":").append(Json.string(user))
                    .append("}]}");
        }
        return json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The assistant's text out of a 2xx chat response, or {@code null} when the body carries none.
     *
     * <p>Null is a real case, not just a parse failure: a reasoning model that spends its whole
     * output cap on thinking returns a well-formed response with no text part in it. The caller
     * treats that as a panelist that didn't answer, not as a crash.
     */
    static String text(String provider, String jsonBody) {
        Object parsed;
        try {
            parsed = new Yaml().load(jsonBody);
        } catch (Exception e) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        String text;
        if ("anthropic".equals(provider)) {
            text = anthropicText(root);
        } else if ("google".equals(provider)) {
            text = googleText(root);
        } else {
            text = openAiText(root);
        }
        return text == null || text.trim().isEmpty() ? null : text;
    }

    /** Anthropic: {@code content} is a block list; the text blocks, joined. */
    private static String anthropicText(Map<?, ?> root) {
        Object content = root.get("content");
        if (!(content instanceof List)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Object blockObj : (List<?>) content) {
            if (!(blockObj instanceof Map)) {
                continue;
            }
            Map<?, ?> block = (Map<?, ?>) blockObj;
            if ("text".equals(block.get("type")) && block.get("text") instanceof String) {
                out.append((String) block.get("text"));
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** OpenAI-compatible: {@code choices[0].message.content}. */
    private static String openAiText(Map<?, ?> root) {
        Object choices = root.get("choices");
        if (!(choices instanceof List) || ((List<?>) choices).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) choices).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object message = ((Map<?, ?>) first).get("message");
        if (!(message instanceof Map)) {
            return null;
        }
        Object content = ((Map<?, ?>) message).get("content");
        return content instanceof String ? (String) content : null;
    }

    /** Gemini: {@code candidates[0].content.parts[].text}, joined. */
    private static String googleText(Map<?, ?> root) {
        Object candidates = root.get("candidates");
        if (!(candidates instanceof List) || ((List<?>) candidates).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) candidates).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object content = ((Map<?, ?>) first).get("content");
        if (!(content instanceof Map)) {
            return null;
        }
        Object parts = ((Map<?, ?>) content).get("parts");
        if (!(parts instanceof List)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Object partObj : (List<?>) parts) {
            if (partObj instanceof Map && ((Map<?, ?>) partObj).get("text") instanceof String) {
                // A thinking model marks its internal parts `thought: true`; those are not the
                // answer and must not be pasted into one.
                if (Boolean.TRUE.equals(((Map<?, ?>) partObj).get("thought"))) {
                    continue;
                }
                out.append((String) ((Map<?, ?>) partObj).get("text"));
            }
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** A short, human-readable provider name for the transcript this proxy hands back. */
    static String displayName(String provider) {
        switch (provider) {
            case "anthropic":
                return "Claude";
            case "openai":
                return "GPT";
            case "google":
                return "Gemini";
            case "mistral":
                return "Mistral";
            case "kimi":
                return "Kimi";
            default:
                return provider.toUpperCase(Locale.ROOT);
        }
    }
}
