package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Gemini's speech: asked for through generateContent, answered with base64 PCM inside JSON, and
 * handed back to the caller as MP3 like every other audio provider — see {@link Mp3Encoder}.
 */
class GeminiSpeechTest {

    /** A tone, as Gemini sends speech: signed 16-bit little-endian mono. */
    private static byte[] pcmTone(double seconds, int rate) {
        int samples = (int) (seconds * rate);
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short value = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000);
            pcm[2 * i] = (byte) value;
            pcm[2 * i + 1] = (byte) (value >> 8);
        }
        return pcm;
    }

    private static byte[] geminiReply(byte[] pcm, String mimeType) {
        String json = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\""
                + mimeType + "\",\"data\":\"" + Base64.getEncoder().encodeToString(pcm)
                + "\"}}],\"role\":\"model\"},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":9}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** An MPEG audio frame starts with eleven set bits. */
    private static boolean startsWithMp3Frame(byte[] bytes) {
        return bytes.length > 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0;
    }

    @Test
    void asksGenerateContentForAudioInTheNamedVoice() {
        MediaAdapter.Request request =
                MediaAdapter.audio("google", "gemini-3.8-flash-tts", "Kore", "Kaixo mundua");
        assertEquals("/v1beta/models/gemini-3.8-flash-tts:generateContent", request.path());
        String body = new String(request.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"responseModalities\":[\"AUDIO\"]"));
        assertTrue(body.contains("\"voiceName\":\"Kore\""));
        assertTrue(body.contains("\"text\":\"Kaixo mundua\""));
    }

    @Test
    void thePcmComesBackAsMp3() {
        MediaAdapter.Media media = MediaAdapter.read(Capability.AUDIO, "google",
                geminiReply(pcmTone(1.0, 24000), "audio/L16;codec=pcm;rate=24000"));
        assertNotNull(media);
        assertEquals("audio/mpeg", media.mediaType());
        byte[] mp3 = Base64.getDecoder().decode(media.base64());
        assertTrue(startsWithMp3Frame(mp3), "the caller gets MP3, as from every other audio provider");
        // 64 kbps for a second is 8 KB; PCM for the same second is 48 KB.
        assertTrue(mp3.length < 16_000, "and a fraction of the PCM's size: " + mp3.length);
    }

    @Test
    void aMinuteAndMoreOfSpeechIsReadDespiteItsSize() {
        // ~4.6 MB of base64 — past the 3 MB a SnakeYAML parse would accept.
        byte[] reply = geminiReply(pcmTone(72, 24000), "audio/L16;codec=pcm;rate=24000");
        assertTrue(reply.length > 3 * 1024 * 1024);
        MediaAdapter.Media media = MediaAdapter.read(Capability.AUDIO, "google", reply);
        assertNotNull(media, "a long passage is still a passage");
        assertTrue(startsWithMp3Frame(Base64.getDecoder().decode(media.base64())));
    }

    @Test
    void aReplyWithoutAudioIsNoAnswer() {
        String refusal = "{\"candidates\":[{\"finishReason\":\"OTHER\"}]}";
        assertNull(MediaAdapter.read(Capability.AUDIO, "google", refusal.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theRateComesFromTheMediaType() {
        assertEquals(24000, MediaAdapter.pcmRate("audio/L16;codec=pcm;rate=24000"));
        assertEquals(16000, MediaAdapter.pcmRate("audio/L16; rate=16000"));
        assertEquals(24000, MediaAdapter.pcmRate(null));
        assertEquals(24000, MediaAdapter.pcmRate("audio/L16"));
    }

    @Test
    void googleSpeaksButIsNeverChosenOverTheOthersUnasked() {
        assertTrue(MediaAdapter.supportsAudio("google"));
    }
}
