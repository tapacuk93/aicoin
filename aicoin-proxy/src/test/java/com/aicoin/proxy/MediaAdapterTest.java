package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The image and speech request shapes, and reading the results back out of three different ones. */
class MediaAdapterTest {

    private static String bodyOf(MediaAdapter.Request request) {
        return new String(request.body(), StandardCharsets.UTF_8);
    }

    @Test
    void openAiImageNamesTheModelAndSizeInTheBody() {
        MediaAdapter.Request request = MediaAdapter.image("openai", "gpt-image-1", "a red circle", "512x512");
        assertEquals("/v1/images/generations", request.path());
        String body = bodyOf(request);
        assertTrue(body.contains("\"model\":\"gpt-image-1\""));
        assertTrue(body.contains("\"size\":\"512x512\""));
        // gpt-image-1 rejects response_format outright and always answers base64.
        assertFalse(body.contains("response_format"));
    }

    @Test
    void stabilityNamesTheEngineInThePathAndTheSizeAsNumbers() {
        MediaAdapter.Request request =
                MediaAdapter.image("stability", "stable-diffusion-xl-1024-v1-0", "a red circle", "1024x768");
        assertEquals("/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image", request.path());
        String body = bodyOf(request);
        assertTrue(body.contains("\"width\":1024"));
        assertTrue(body.contains("\"height\":768"));
        assertTrue(body.contains("\"text_prompts\""));
        // JSON rather than the binary PNG it would otherwise return.
        assertTrue(headerValue(request, "accept").contains("application/json"));
    }

    @Test
    void elevenLabsNamesTheVoiceInThePathAndAsksForAudioBack() {
        MediaAdapter.Request request =
                MediaAdapter.audio("elevenlabs", "eleven_multilingual_v2", "VOICE123", "hello there");
        assertEquals("/v1/text-to-speech/VOICE123", request.path());
        assertEquals("audio/mpeg", headerValue(request, "accept"));
        assertTrue(bodyOf(request).contains("\"model_id\":\"eleven_multilingual_v2\""));
    }

    @Test
    void openAiSpeechNamesTheVoiceInTheBody() {
        MediaAdapter.Request request = MediaAdapter.audio("openai", "gpt-4o-mini-tts", "alloy", "hello there");
        assertEquals("/v1/audio/speech", request.path());
        String body = bodyOf(request);
        assertTrue(body.contains("\"voice\":\"alloy\""));
        assertTrue(body.contains("\"input\":\"hello there\""));
    }

    @Test
    void aPromptWithQuotesInItIsEscapedNotBroken() {
        String body = bodyOf(MediaAdapter.image("openai", "gpt-image-1", "a sign reading \"open\"", "1024x1024"));
        assertTrue(body.contains("\\\"open\\\""), body);
    }

    @Test
    void readsBase64OutOfEachProvidersOwnShape() {
        MediaAdapter.Media openai = MediaAdapter.read(Capability.IMAGE, "openai",
                "{\"data\":[{\"b64_json\":\"QUJD\"}]}".getBytes(StandardCharsets.UTF_8));
        assertNotNull(openai);
        assertEquals("QUJD", openai.base64());
        assertEquals("image/png", openai.mediaType());

        MediaAdapter.Media stability = MediaAdapter.read(Capability.IMAGE, "stability",
                "{\"artifacts\":[{\"base64\":\"WFla\"}]}".getBytes(StandardCharsets.UTF_8));
        assertNotNull(stability);
        assertEquals("WFla", stability.base64());
    }

    @Test
    void audioArrivesAsBytesAndLeavesAsBase64() {
        byte[] mp3 = new byte[] {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00, 0x42};
        MediaAdapter.Media media = MediaAdapter.read(Capability.AUDIO, "elevenlabs", mp3);
        assertNotNull(media);
        assertEquals("audio/mpeg", media.mediaType());
        assertEquals(Base64.getEncoder().encodeToString(mp3), media.base64());
    }

    @Test
    void aJsonBodyWhereAudioWasExpectedIsNotAudio() {
        // Providers do answer 2xx with a JSON error shape. Base64-ing it and calling it an MP3
        // would hand the caller a file that plays nothing, and charge them for it.
        assertNull(MediaAdapter.read(Capability.AUDIO, "elevenlabs",
                "{\"detail\":\"quota exceeded\"}".getBytes(StandardCharsets.UTF_8)));
        assertNull(MediaAdapter.read(Capability.AUDIO, "openai", new byte[0]));
    }

    @Test
    void anEmptyOrUnreadableImageResponseIsNoImage() {
        assertNull(MediaAdapter.read(Capability.IMAGE, "openai",
                "{\"data\":[]}".getBytes(StandardCharsets.UTF_8)));
        assertNull(MediaAdapter.read(Capability.IMAGE, "stability",
                "not json at all".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anUnreadableSizeFallsBackRatherThanFailingTheCall() {
        assertArrayEqualsInt(new int[] {1024, 1024}, MediaAdapter.parseSize(null));
        assertArrayEqualsInt(new int[] {1024, 1024}, MediaAdapter.parseSize("enormous"));
        assertArrayEqualsInt(new int[] {512, 768}, MediaAdapter.parseSize("512x768"));
    }

    @Test
    void eachCapabilityKnowsWhichProvidersCanServeIt() {
        assertTrue(MediaAdapter.supports(Capability.IMAGE, "openai"));
        assertTrue(MediaAdapter.supports(Capability.IMAGE, "stability"));
        assertFalse(MediaAdapter.supports(Capability.IMAGE, "anthropic"));
        assertTrue(MediaAdapter.supports(Capability.AUDIO, "elevenlabs"));
        assertFalse(MediaAdapter.supports(Capability.AUDIO, "stability"));
        // Text falls through to the chat adapter's own list.
        assertTrue(MediaAdapter.supports(Capability.TEXT, "anthropic"));
        assertFalse(MediaAdapter.supports(Capability.TEXT, "elevenlabs"));
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(expected[0], actual[0]);
        assertEquals(expected[1], actual[1]);
    }

    private static String headerValue(MediaAdapter.Request request, String name) {
        for (Map.Entry<String, String> header : request.headers()) {
            if (header.getKey().equalsIgnoreCase(name)) {
                return header.getValue();
            }
        }
        return "";
    }
}
