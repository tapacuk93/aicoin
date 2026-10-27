package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminAlertsTest {

    @Test
    void anthropicCreditErrorIsACreditError() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":"
                + "\"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits.\"}}";
        assertTrue(AdminAlerts.isCreditError(400, body));
    }

    @Test
    void openAiQuotaAndGoogleExhaustionAreCreditErrors() {
        assertTrue(AdminAlerts.isCreditError(429, "{\"error\":{\"code\":\"insufficient_quota\"}}"));
        assertTrue(AdminAlerts.isCreditError(429, "{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}"));
        assertTrue(AdminAlerts.isCreditError(402, ""));
    }

    @Test
    void ordinaryErrorsAreNot() {
        assertFalse(AdminAlerts.isCreditError(400, "{\"error\":{\"message\":\"max_tokens: field required\"}}"));
        assertFalse(AdminAlerts.isCreditError(429, "{\"error\":{\"type\":\"rate_limit_error\"}}"));
        assertFalse(AdminAlerts.isCreditError(500, null));
    }

    @Test
    void errorSpikeNeedsVolumeAndShare() {
        assertFalse(AdminAlerts.isErrorSpike(4, 4));
        assertTrue(AdminAlerts.isErrorSpike(20, 5));
        assertFalse(AdminAlerts.isErrorSpike(100, 5));
        assertFalse(AdminAlerts.isErrorSpike(0, 0));
    }

    @Test
    void budgetLevels() {
        assertEquals(Optional.empty(), AdminAlerts.budgetLevel(499, 1000));
        assertEquals(Optional.of("half"), AdminAlerts.budgetLevel(500, 1000));
        assertEquals(Optional.of("half"), AdminAlerts.budgetLevel(899, 1000));
        assertEquals(Optional.of("near"), AdminAlerts.budgetLevel(900, 1000));
        assertEquals(Optional.of("exhausted"), AdminAlerts.budgetLevel(1000, 1000));
    }

    @Test
    void hooksAreSafeBeforeInstall() {
        AdminAlerts noop = new AdminAlerts(null, null, null, Map.of());
        noop.upstreamResponse("anthropic", 400, "credit balance".getBytes(StandardCharsets.UTF_8));
        noop.clientResponse(500);
        noop.iapFailed("x", null);
        noop.proxyStarted();
        assertFalse(noop.isApnsConfigured());
    }

    @Test
    void registrationParsing() {
        String token = "a".repeat(64);
        AlertsHandler.Registration ok = AlertsHandler.parseRegistration(
                "{\"token\":\"" + token + "\",\"environment\":\"development\",\"disabled\":[\"deploy\",\"iap_purchase\"]}");
        assertNull(ok.error());
        assertTrue(ok.device().development());
        assertEquals(List.of("deploy", "iap_purchase"), ok.device().disabled());

        assertNotNull(AlertsHandler.parseRegistration("{\"token\":\"zz\"}").error());
        assertNotNull(AlertsHandler.parseRegistration("{\"token\":\"" + token + "\",\"disabled\":[\"nope\"]}").error());
        assertNotNull(AlertsHandler.parseRegistration("{\"token\":\"" + token + "\",\"environment\":\"staging\"}").error());
        assertFalse(AlertsHandler.parseRegistration("{\"token\":\"" + token + "\"}").device().development());
    }

    @Test
    void deviceRoundTrips() {
        AdminAlerts.Device d = new AdminAlerts.Device("ab".repeat(32), true, List.of("deploy"));
        AdminAlerts.Device back = AdminAlerts.Device.fromJson(d.token(), d.toJson());
        assertTrue(back.development());
        assertEquals(List.of("deploy"), back.disabled());
    }

    @Test
    void payloadIsValidJsonWithAnAlert() {
        Object parsed = Json.load(Apns.payload("Ti\"tle", "Bo\ndy", "budget"));
        Map<?, ?> aps = (Map<?, ?>) ((Map<?, ?>) parsed).get("aps");
        assertEquals("Ti\"tle", ((Map<?, ?>) aps.get("alert")).get("title"));
    }

    @Test
    void providerTokenIsAVerifiableEs256Jwt() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\\n-----END PRIVATE KEY-----";
        Apns apns = Apns.fromEnv(Map.of("AICOIN_PROXY_APNS_KEY", pem, "AICOIN_PROXY_APNS_KEY_ID", "ABCDE12345"));
        assertTrue(apns.isConfigured());
        String jwt = apns.providerToken(1_700_000_000L);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(header.contains("\"kid\":\"ABCDE12345\"") && header.contains("ES256"), header);
        assertTrue(claims.contains("\"iss\":\"HTS38ZPRVH\"") && claims.contains("\"iat\":1700000000"), claims);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(pair.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
        // Reused within its lifetime.
        assertEquals(jwt, apns.providerToken(1_700_000_000L + 600));
    }

    @Test
    void unconfiguredApnsSendsNothing() {
        Apns apns = Apns.fromEnv(Map.of());
        assertFalse(apns.isConfigured());
        assertFalse(apns.send("ab", true, "t", "b", "test").join().ok());
    }
}
