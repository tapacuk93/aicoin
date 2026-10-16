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

    /**
     * What gemini-3.8-flash-tts really answers (a Basque phrase, recorded from the API): not bare
     * PCM but a WAV file, with Google's 6 KB C2PA provenance chunk after the samples.
     */
    @Test
    void aRealReplyIsReadAsTheWavItIsNotAsBareSamples() throws Exception {
        byte[] reply;
        try (java.io.InputStream in = getClass().getResourceAsStream("/gemini-tts-eu.json")) {
            reply = in.readAllBytes();
        }
        String body = new String(reply, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"mimeType\": \"audio/wav\"") || body.contains("\"mimeType\":\"audio/wav\""));

        int start = body.indexOf("\"data\"");
        String b64 = body.substring(body.indexOf('"', body.indexOf(':', start) + 1) + 1);
        b64 = b64.substring(0, b64.indexOf('"'));
        MediaAdapter.Wav wav = MediaAdapter.Wav.read(Base64.getDecoder().decode(b64));
        assertNotNull(wav);
        assertEquals(24000, wav.sampleRate);
        assertEquals(1, wav.channels);
        assertEquals(122_880, wav.samples.length, "exactly the data chunk — not the header, not the C2PA manifest after it");

        MediaAdapter.Media media = MediaAdapter.read(Capability.AUDIO, "google", reply);
        assertNotNull(media);
        byte[] mp3 = Base64.getDecoder().decode(media.base64());
        assertTrue(startsWithMp3Frame(mp3));
        // 2.56 s at 64 kbps is ~20 KB; an encoder fed the manifest as sound would run longer.
        assertTrue(mp3.length > 15_000 && mp3.length < 25_000, "mp3 bytes: " + mp3.length);
    }

    @Test
    void chunksAfterTheSamplesAreNotSamples() {
        byte[] samples = pcmTone(0.1, 24000);
        java.io.ByteArrayOutputStream wav = new java.io.ByteArrayOutputStream();
        wav.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(le32(0));
        wav.writeBytes("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(le32(16));
        wav.writeBytes(new byte[] {1, 0, 1, 0});
        wav.writeBytes(le32(24000));
        wav.writeBytes(le32(48000));
        wav.writeBytes(new byte[] {2, 0, 16, 0});
        wav.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(le32(samples.length));
        wav.writeBytes(samples);
        wav.writeBytes("C2PA".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(le32(4));
        wav.writeBytes(new byte[] {9, 9, 9, 9});
        MediaAdapter.Wav read = MediaAdapter.Wav.read(wav.toByteArray());
        assertNotNull(read);
        assertEquals(samples.length, read.samples.length);
        assertNull(MediaAdapter.Wav.read(samples), "bare samples are not a WAV file");
    }

    private static byte[] le32(int value) {
        return new byte[] {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
    }

    /** Priced by the audio it reports, not a flat price per clip — pieces of one passage add up. */
    @Test
    void speechIsPricedByItsOwnTokenCounts() throws Exception {
        byte[] reply;
        try (java.io.InputStream in = getClass().getResourceAsStream("/gemini-tts-eu.json")) {
            reply = in.readAllBytes();
        }
        ModelPricing pricing = ProxyConfig.load(java.util.Map.of()).getModelPricing();
        assertNull(CostCalculator.priceKnownModel("google", "gemini-3.8-flash-tts", pricing),
                "no flat per-clip price any more");
        CostCalculator.Priced priced = CostCalculator.price("google", new String(reply, StandardCharsets.UTF_8), pricing);
        // 11 text tokens in, 64 audio tokens out (2.56 s of speech).
        assertEquals(11 * 0.50e-6 + 64 * 9.00e-6, priced.getCostUsd(), 1e-9);
        assertEquals(75, priced.getTokens());
    }

    @Test
    void aReplyTooLargeToParseStillHasItsUsageRead() {
        String audio = "A".repeat(4 * 1024 * 1024);
        String body = "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/wav\",\"data\":\""
                + audio + "\"}}]}}],\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":1500,"
                + "\"candidatesTokensDetails\":[{\"modality\":\"AUDIO\",\"tokenCount\":1500}]},\"modelVersion\":\"gemini-3.8-flash-tts\"}";
        ModelPricing pricing = ProxyConfig.load(java.util.Map.of()).getModelPricing();
        assertEquals(100 * 0.50e-6 + 1500 * 9.00e-6, CostCalculator.price("google", body, pricing).getCostUsd(), 1e-9);
    }

    @Test
    void googleSpeaksButIsNeverChosenOverTheOthersUnasked() {
        assertTrue(MediaAdapter.supportsAudio("google"));
    }
}
