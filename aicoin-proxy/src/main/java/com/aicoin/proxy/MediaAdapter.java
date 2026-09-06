package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * What {@link ChatAdapter} is for chat, this is for images and speech: the one place this proxy
 * writes a provider's image or text-to-speech request itself, and reads the result back out.
 *
 * <p>The shapes have nothing in common. OpenAI takes a prompt and a size and returns base64 inside
 * JSON; Stability takes a weighted prompt list and returns base64 in an {@code artifacts} array;
 * ElevenLabs names the voice in the path and returns raw MP3 bytes with no JSON anywhere. A caller
 * asking for a picture should not have to know any of that, which is the whole reason {@code POST
 * /image} and {@code POST /audio} exist — so the difference is absorbed here, once.
 *
 * <p>Everything comes back to the caller as base64 in JSON, including the providers that answer in
 * bytes. That is a deliberate cost: base64 is a third larger than the bytes it carries. It buys one
 * response shape across providers, which is what makes the choice of provider genuinely the
 * proxy's to make rather than something the caller has to branch on.
 */
final class MediaAdapter {

    private MediaAdapter() {
    }

    /** One composed request: where to send it, what headers it needs, and the bytes. */
    static final class Request {
        private final String path;
        private final List<Map.Entry<String, String>> headers;
        private final byte[] body;

        Request(String path, List<Map.Entry<String, String>> headers, byte[] body) {
            this.path = path;
            this.headers = headers;
            this.body = body;
        }

        String path() {
            return path;
        }

        List<Map.Entry<String, String>> headers() {
            return headers;
        }

        byte[] body() {
            return body;
        }
    }

    /** What came back: the bytes themselves, base64-encoded, and what kind of bytes they are. */
    static final class Media {
        private final String base64;
        private final String mediaType;

        Media(String base64, String mediaType) {
            this.base64 = base64;
            this.mediaType = mediaType;
        }

        String base64() {
            return base64;
        }

        String mediaType() {
            return mediaType;
        }
    }

    static boolean supportsImage(String provider) {
        return "openai".equals(provider) || "stability".equals(provider);
    }

    static boolean supportsAudio(String provider) {
        return "elevenlabs".equals(provider) || "openai".equals(provider);
    }

    static boolean supports(Capability capability, String provider) {
        switch (capability) {
            case IMAGE:
                return supportsImage(provider);
            case AUDIO:
                return supportsAudio(provider);
            default:
                return ChatAdapter.supports(provider);
        }
    }

    /**
     * A text-to-image request.
     *
     * @param size {@code WIDTHxHEIGHT}, already validated by the caller
     */
    static Request image(String provider, String model, String prompt, String size) {
        if ("stability".equals(provider)) {
            int[] dimensions = parseSize(size);
            String json = "{\"text_prompts\":[{\"text\":" + Json.string(prompt) + ",\"weight\":1}]"
                    + ",\"width\":" + dimensions[0] + ",\"height\":" + dimensions[1]
                    + ",\"samples\":1,\"steps\":30}";
            // Stability answers with a PNG binary unless told otherwise; JSON keeps one code path
            // for reading the result.
            return new Request("/v1/generation/" + model + "/text-to-image",
                    headers("application/json"), json.getBytes(StandardCharsets.UTF_8));
        }
        // OpenAI. gpt-image-1 always answers with base64 and rejects response_format outright, so
        // it is not sent — the older DALL·E models default to a URL, which this would need.
        String json = "{\"model\":" + Json.string(model) + ",\"prompt\":" + Json.string(prompt)
                + ",\"size\":" + Json.string(size) + ",\"n\":1}";
        return new Request("/v1/images/generations", headers("application/json"),
                json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A text-to-speech request.
     *
     * @param voice the provider's own voice identifier — a voice id for ElevenLabs, a voice name
     *              for OpenAI
     */
    static Request audio(String provider, String model, String voice, String text) {
        if ("elevenlabs".equals(provider)) {
            String json = "{\"text\":" + Json.string(text) + ",\"model_id\":" + Json.string(model) + "}";
            return new Request("/v1/text-to-speech/" + voice, headers("audio/mpeg"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        String json = "{\"model\":" + Json.string(model) + ",\"voice\":" + Json.string(voice)
                + ",\"input\":" + Json.string(text) + ",\"response_format\":\"mp3\"}";
        return new Request("/v1/audio/speech", headers("audio/mpeg"), json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a 2xx response into base64 and a media type, or null when it holds no media.
     *
     * <p>Null is the same kind of case it is in {@link ChatAdapter}: a well-formed response that
     * carries no result — a moderation refusal, an empty artifact list — is a provider that did not
     * answer, not a crash.
     */
    static Media read(Capability capability, String provider, byte[] responseBody) {
        if (capability == Capability.AUDIO) {
            // Both audio providers answer in raw bytes. A JSON body here is an error shape that
            // arrived with a 2xx status, which is not audio however well-formed it is.
            if (responseBody.length == 0 || responseBody[0] == '{') {
                return null;
            }
            return new Media(Base64.getEncoder().encodeToString(responseBody), "audio/mpeg");
        }
        String body = new String(responseBody, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = new Yaml().load(body);
        } catch (Exception e) {
            return null;
        }
        if (!(parsed instanceof Map)) {
            return null;
        }
        Map<?, ?> root = (Map<?, ?>) parsed;
        String base64 = "stability".equals(provider) ? stabilityImage(root) : openAiImage(root);
        return base64 == null ? null : new Media(base64, "image/png");
    }

    /** OpenAI: {@code data[0].b64_json}. */
    private static String openAiImage(Map<?, ?> root) {
        Object data = root.get("data");
        if (!(data instanceof List) || ((List<?>) data).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) data).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object b64 = ((Map<?, ?>) first).get("b64_json");
        return b64 instanceof String ? (String) b64 : null;
    }

    /** Stability: {@code artifacts[0].base64}. */
    private static String stabilityImage(Map<?, ?> root) {
        Object artifacts = root.get("artifacts");
        if (!(artifacts instanceof List) || ((List<?>) artifacts).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) artifacts).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        Object base64 = ((Map<?, ?>) first).get("base64");
        return base64 instanceof String ? (String) base64 : null;
    }

    /** @return {@code {width, height}} from a {@code WIDTHxHEIGHT} string; 1024x1024 if unreadable. */
    static int[] parseSize(String size) {
        if (size != null) {
            int x = size.indexOf('x');
            if (x > 0) {
                try {
                    return new int[] {Integer.parseInt(size.substring(0, x)),
                            Integer.parseInt(size.substring(x + 1))};
                } catch (NumberFormatException ignored) {
                    // Falls through to the default below.
                }
            }
        }
        return new int[] {1024, 1024};
    }

    private static List<Map.Entry<String, String>> headers(String accept) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        headers.add(new AbstractMap.SimpleEntry<>("content-type", "application/json"));
        headers.add(new AbstractMap.SimpleEntry<>("accept", accept));
        // Nothing here relays the body onward, so an inflated one is only work to undo.
        headers.add(new AbstractMap.SimpleEntry<>("accept-encoding", "identity"));
        return headers;
    }
}
