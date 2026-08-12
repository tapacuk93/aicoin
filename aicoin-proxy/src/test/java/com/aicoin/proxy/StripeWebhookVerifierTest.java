package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The card path's entire security boundary: {@code POST /checkout/webhook} is public and credits a
 * wallet, so every one of these is a way someone could otherwise mint coins for free.
 */
class StripeWebhookVerifierTest {

    private static final String SECRET = "whsec_test_2Ns8kQpL9vXzR4mT";
    private static final String BODY =
            "{\"id\":\"evt_1\",\"type\":\"checkout.session.completed\","
            + "\"data\":{\"object\":{\"id\":\"cs_test_a1\",\"metadata\":{\"address\":\"alice\"}}}}";
    private static final long NOW = 1_700_000_000L;

    private static String sign(long timestamp, String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] body() {
        return BODY.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsAGenuineSignature() {
        String header = "t=" + NOW + ",v1=" + sign(NOW, BODY, SECRET);
        assertTrue(StripeWebhookVerifier.verify(header, body(), SECRET, NOW));
    }

    @Test
    void rejectsATamperedBody() {
        // The attack this exists to stop: take a real webhook, point it at a different wallet.
        String header = "t=" + NOW + ",v1=" + sign(NOW, BODY, SECRET);
        String tampered = BODY.replace("alice", "mallory");
        assertFalse(StripeWebhookVerifier.verify(header, tampered.getBytes(StandardCharsets.UTF_8), SECRET, NOW));
    }

    @Test
    void rejectsASignatureFromTheWrongSecret() {
        String header = "t=" + NOW + ",v1=" + sign(NOW, BODY, "whsec_someone_elses_secret");
        assertFalse(StripeWebhookVerifier.verify(header, body(), SECRET, NOW));
    }

    @Test
    void rejectsAReplayOutsideTheTolerance() {
        long old = NOW - StripeWebhookVerifier.DEFAULT_TOLERANCE_SECONDS - 1;
        String header = "t=" + old + ",v1=" + sign(old, BODY, SECRET);
        // Correctly signed for its own timestamp, so only the age check can catch it.
        assertTrue(StripeWebhookVerifier.verify(header, body(), SECRET, old));
        assertFalse(StripeWebhookVerifier.verify(header, body(), SECRET, NOW));
    }

    @Test
    void rejectsAFutureTimestampOutsideTheTolerance() {
        long future = NOW + StripeWebhookVerifier.DEFAULT_TOLERANCE_SECONDS + 1;
        String header = "t=" + future + ",v1=" + sign(future, BODY, SECRET);
        assertFalse(StripeWebhookVerifier.verify(header, body(), SECRET, NOW));
    }

    @Test
    void acceptsWhenOneOfSeveralSignaturesMatches() {
        // Stripe sends more than one v1 while a signing secret is being rotated.
        String header = "t=" + NOW
                + ",v1=" + sign(NOW, BODY, "whsec_old_secret")
                + ",v1=" + sign(NOW, BODY, SECRET);
        assertTrue(StripeWebhookVerifier.verify(header, body(), SECRET, NOW));
    }

    @Test
    void rejectsAHeaderCarryingNoSignatureAtAll() {
        assertFalse(StripeWebhookVerifier.verify("t=" + NOW, body(), SECRET, NOW));
        assertFalse(StripeWebhookVerifier.verify("v1=" + sign(NOW, BODY, SECRET), body(), SECRET, NOW));
        assertFalse(StripeWebhookVerifier.verify("", body(), SECRET, NOW));
        assertFalse(StripeWebhookVerifier.verify(null, body(), SECRET, NOW));
    }

    @Test
    void rejectsMalformedInput() {
        assertFalse(StripeWebhookVerifier.verify("t=notanumber,v1=abcd", body(), SECRET, NOW));
        assertFalse(StripeWebhookVerifier.verify("t=" + NOW + ",v1=zzzz", body(), SECRET, NOW));
        assertFalse(StripeWebhookVerifier.verify("t=" + NOW + ",v1=abc", body(), SECRET, NOW));
    }

    @Test
    void refusesToVerifyWithoutAConfiguredSecret() {
        // An unset secret must fail closed. Treating "" as "skip verification" would leave the
        // endpoint wide open on any deployment that simply forgot to configure it.
        String header = "t=" + NOW + ",v1=" + sign(NOW, BODY, SECRET);
        assertFalse(StripeWebhookVerifier.verify(header, body(), "", NOW));
        assertFalse(StripeWebhookVerifier.verify(header, body(), null, NOW));
    }

    @Test
    void signatureIsOverTheExactBytesReceived() {
        // Re-serialized JSON differs in whitespace, so verification must run on the raw body.
        String header = "t=" + NOW + ",v1=" + sign(NOW, BODY, SECRET);
        String reserialized = BODY.replace(",\"", ", \"");
        assertFalse(StripeWebhookVerifier.verify(header, reserialized.getBytes(StandardCharsets.UTF_8), SECRET, NOW));
    }
}
