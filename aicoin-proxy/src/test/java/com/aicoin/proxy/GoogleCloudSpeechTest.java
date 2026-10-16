package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Google Cloud Text-to-Speech, for Malagasy: a service account's token in place of a key, the
 * language named in the request, and MP3 that needs no conversion.
 */
class GoogleCloudSpeechTest {

    @Test
    void namesTheLanguageAndModelAndAsksForMp3() {
        MediaAdapter.Request request = MediaAdapter.audio("googletts", "gemini-3.1-flash-tts-preview",
                "Kore", "Salama tompoko.", "mg-MG");
        assertEquals("/v1/text:synthesize", request.path());
        String body = new String(request.body(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"languageCode\":\"mg-MG\""), "Cloud TTS does not guess the language");
        assertTrue(body.contains("\"modelName\":\"gemini-3.1-flash-tts-preview\""));
        assertTrue(body.contains("\"audioEncoding\":\"LINEAR16\""), "samples, so pieces join; encoded to MP3 here");
    }

    @Test
    void itsWavIsEncodedToMp3() {
        byte[] samples = new byte[4800];
        java.io.ByteArrayOutputStream wav = new java.io.ByteArrayOutputStream();
        wav.writeBytes("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1));
        wav.writeBytes(new byte[] {16, 0, 0, 0, 1, 0, 1, 0, (byte) 0xC0, 0x5D, 0, 0, (byte) 0x80, (byte) 0xBB, 0, 0, 2, 0, 16, 0});
        wav.writeBytes("data".getBytes(StandardCharsets.ISO_8859_1));
        wav.writeBytes(new byte[] {(byte) 0xC0, 0x12, 0, 0});
        wav.writeBytes(samples);
        String reply = "{\n  \"audioContent\": \"" + Base64.getEncoder().encodeToString(wav.toByteArray()) + "\"\n}";
        MediaAdapter.Media media = MediaAdapter.read(Capability.AUDIO, "googletts", reply.getBytes(StandardCharsets.UTF_8));
        assertNotNull(media);
        assertEquals("audio/mpeg", media.mediaType());
        byte[] mp3 = Base64.getDecoder().decode(media.base64());
        assertTrue((mp3[0] & 0xFF) == 0xFF && (mp3[1] & 0xE0) == 0xE0, "an MPEG frame");
        assertNull(MediaAdapter.read(Capability.AUDIO, "googletts", "{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theAssertionIsAnRs256JwtSignedByTheAccountsKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String keyFile = "{\"type\":\"service_account\",\"client_email\":\"tts@example.iam.gserviceaccount.com\","
                + "\"private_key\":" + Json.string(pem) + ",\"token_uri\":\"https://oauth2.googleapis.com/token\"}";

        String jwt = GoogleServiceAccount.fromJson(keyFile).assertion(Instant.ofEpochSecond(1_000_000));
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        Map<?, ?> claims = (Map<?, ?>) new org.yaml.snakeyaml.Yaml().load(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertEquals("tts@example.iam.gserviceaccount.com", claims.get("iss"));
        assertEquals("https://oauth2.googleapis.com/token", claims.get("aud"));
        assertEquals(1_003_600, ((Number) claims.get("exp")).intValue(), "an hour, the most Google allows");

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "signed with the account's own key");
    }

    @Test
    void aMissingKeyFileLeavesTheProviderUnconfigured() {
        assertNull(GoogleServiceAccount.start(""));
        assertNull(GoogleServiceAccount.start("/nonexistent/key.json"));
        assertEquals("", ProxyConfig.load(Map.of()).getProvider("googletts").getApiKey());
    }
}
