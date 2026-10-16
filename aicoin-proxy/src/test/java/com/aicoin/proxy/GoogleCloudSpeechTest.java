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
        assertTrue(body.contains("\"audioEncoding\":\"MP3\""));
    }

    @Test
    void theMp3IsPassedOnAsItCame() {
        String content = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xF3, 0x44, 0x00});
        MediaAdapter.Media media = MediaAdapter.read(Capability.AUDIO, "googletts",
                ("{\n  \"audioContent\": \"" + content + "\"\n}").getBytes(StandardCharsets.UTF_8));
        assertNotNull(media);
        assertEquals(content, media.base64());
        assertEquals("audio/mpeg", media.mediaType());
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
