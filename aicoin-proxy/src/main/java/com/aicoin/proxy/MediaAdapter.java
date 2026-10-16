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
        return "elevenlabs".equals(provider) || "openai".equals(provider) || "google".equals(provider)
                || "googletts".equals(provider);
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
     *              for OpenAI and Google
     */
    static Request audio(String provider, String model, String voice, String text) {
        return audio(provider, model, voice, text, null);
    }

    /**
     * @param language BCP-47 tag of the text's language. Only Cloud TTS needs it — it will not
     *                 guess — and it defaults there to {@code en-US}; every other provider infers
     *                 the language from the text.
     */
    static Request audio(String provider, String model, String voice, String text, String language) {
        if ("googletts".equals(provider)) {
            String json = "{\"input\":{\"text\":" + Json.string(text) + "}"
                    + ",\"voice\":{\"languageCode\":" + Json.string(language == null || language.isBlank() ? "en-US" : language)
                    + ",\"name\":" + Json.string(voice) + ",\"modelName\":" + Json.string(model) + "}"
                    + ",\"audioConfig\":{\"audioEncoding\":\"MP3\"}}";
            return new Request("/v1/text:synthesize", headers("application/json"), json.getBytes(StandardCharsets.UTF_8));
        }
        if ("google".equals(provider)) {
            // Gemini's speech models are reached through the same generateContent call as its chat
            // models, asked for audio instead of text. They cover languages no ElevenLabs model
            // does — Basque, Igbo, Khmer, Sinhala, Albanian, Kinyarwanda, Uyghur, Tigrinya,
            // Sesotho, Haitian Creole — which is why they are here at all.
            String json = "{\"contents\":[{\"parts\":[{\"text\":" + Json.string(text) + "}]}]"
                    + ",\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]"
                    + ",\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":"
                    + Json.string(voice) + "}}}}}";
            return new Request("/v1beta/models/" + model + ":generateContent", headers("application/json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
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
        if (capability == Capability.AUDIO && "google".equals(provider)) {
            return geminiSpeech(responseBody);
        }
        if (capability == Capability.AUDIO && "googletts".equals(provider)) {
            // Already MP3, already base64: {"audioContent": "..."} is passed on as it is. Scanned
            // for, not parsed, for the same size reason as geminiSpeech.
            String body = new String(responseBody, StandardCharsets.UTF_8);
            String content = stringField(body, "audioContent", 0);
            return content == null || content.isEmpty() ? null : new Media(content, "audio/mpeg");
        }
        if (capability == Capability.AUDIO) {
            // ElevenLabs and OpenAI answer in raw bytes. A JSON body here is an error shape that
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

    /**
     * Gemini: {@code candidates[0].content.parts[0].inlineData}, whose {@code data} is base64 of bare
     * PCM and whose {@code mimeType} says its rate ({@code audio/L16;codec=pcm;rate=24000}).
     * Re-encoded as MP3 so this provider answers in the same shape as the others — see {@link
     * Mp3Encoder}.
     *
     * <p>Read by scanning for the two fields rather than parsing the document. A minute of speech
     * is ~4 MB of base64 in one string, which is over SnakeYAML's default 3 MB document limit (so
     * the parse every other reader here uses would refuse it) and would be held twice more as a
     * parsed tree. The fields are plain strings — base64 and a media type contain nothing JSON
     * escapes — so finding the quotes around them is exact.
     */
    static Media geminiSpeech(byte[] responseBody) {
        String body = new String(responseBody, StandardCharsets.UTF_8);
        int inline = body.indexOf("\"inlineData\"");
        if (inline < 0) {
            return null;
        }
        String data = stringField(body, "data", inline);
        if (data == null || data.isEmpty()) {
            return null;
        }
        byte[] pcm;
        try {
            pcm = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String mimeType = stringField(body, "mimeType", inline);
        int rate = pcmRate(mimeType);
        int channels = 1;
        Wav wav = Wav.read(pcm);
        if (wav != null) {
            if (wav.bitsPerSample != 16) {
                return null;
            }
            pcm = wav.samples;
            rate = wav.sampleRate;
            channels = wav.channels;
        }
        if (pcm.length < 2) {
            return null;
        }
        byte[] mp3 = Mp3Encoder.fromPcm16(pcm, rate, channels);
        return new Media(Base64.getEncoder().encodeToString(mp3), "audio/mpeg");
    }

    /**
     * A RIFF/WAVE file's format and the samples of its {@code data} chunk — exactly those, by the
     * chunk's declared length.
     *
     * <p>Gemini's documentation describes bare {@code audio/L16} samples; what {@code
     * gemini-3.8-flash-tts} actually answers is {@code audio/wav}, a whole file — and after its
     * {@code data} chunk comes a 6 KB {@code C2PA} chunk, Google's content-provenance manifest.
     * Read as samples, the header was a click at the start of every clip and the manifest a tenth
     * of a second of noise at the end. The manifest does not survive the conversion to MP3; the
     * watermark Google puts in the audio itself does.
     */
    static final class Wav {
        final int sampleRate;
        final int channels;
        final int bitsPerSample;
        final byte[] samples;

        private Wav(int sampleRate, int channels, int bitsPerSample, byte[] samples) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
            this.samples = samples;
        }

        /** Null when {@code bytes} is not a RIFF/WAVE file with a format and a data chunk. */
        static Wav read(byte[] bytes) {
            if (bytes.length < 12 || !ascii(bytes, 0, "RIFF") || !ascii(bytes, 8, "WAVE")) {
                return null;
            }
            int rate = 0;
            int channels = 0;
            int bits = 0;
            int offset = 12;
            while (offset + 8 <= bytes.length) {
                long size = le32(bytes, offset + 4);
                int body = offset + 8;
                if (ascii(bytes, offset, "fmt ") && body + 16 <= bytes.length) {
                    channels = le16(bytes, body + 2);
                    rate = (int) le32(bytes, body + 4);
                    bits = le16(bytes, body + 14);
                } else if (ascii(bytes, offset, "data")) {
                    if (rate <= 0 || channels <= 0) {
                        return null;
                    }
                    // A streaming writer may leave the length unset (0 or 0xFFFFFFFF); then the
                    // samples run to the end of the file.
                    long end = size == 0 || size == 0xFFFFFFFFL ? bytes.length : Math.min(bytes.length, body + size);
                    return new Wav(rate, channels, bits, java.util.Arrays.copyOfRange(bytes, body, (int) end));
                }
                // Chunks are padded to an even length.
                offset = (int) Math.min((long) bytes.length, body + size + (size & 1));
            }
            return null;
        }

        private static boolean ascii(byte[] bytes, int at, String tag) {
            if (at + 4 > bytes.length) {
                return false;
            }
            for (int i = 0; i < 4; i++) {
                if (bytes[at + i] != tag.charAt(i)) {
                    return false;
                }
            }
            return true;
        }

        private static int le16(byte[] b, int at) {
            return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
        }

        private static long le32(byte[] b, int at) {
            return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
        }
    }

    /** The {@code rate=} parameter of an {@code audio/L16} media type; Gemini's 24 kHz if absent. */
    static int pcmRate(String mimeType) {
        if (mimeType != null) {
            for (String part : mimeType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("rate=")) {
                    try {
                        return Integer.parseInt(trimmed.substring(5).trim());
                    } catch (NumberFormatException ignored) {
                        // Falls through to the default.
                    }
                }
            }
        }
        return 24000;
    }

    /** The string value of the first {@code "name":} at or after {@code from}, or null. */
    private static String stringField(String json, String name, int from) {
        int key = json.indexOf("\"" + name + "\"", from);
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key + name.length() + 2);
        int open = colon < 0 ? -1 : json.indexOf('"', colon + 1);
        int close = open < 0 ? -1 : json.indexOf('"', open + 1);
        return close < 0 ? null : json.substring(open + 1, close);
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
