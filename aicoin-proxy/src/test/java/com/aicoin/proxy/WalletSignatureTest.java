package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link WalletSignature} against genuinely-generated Ed25519
 * keypairs (Java's own {@code KeyPairGenerator}/{@code Signature}
 * "Ed25519" providers) so both the live-signature scheme (claim/transfer/
 * revoke) and the token scheme (AI-proxy calls) are verified against real
 * cryptography, not just mocked inputs.
 */
class WalletSignatureTest {

    private static final int SKEW_SECONDS = 120;

    // ---- test helpers: generate a real keypair, derive its hex address, sign with it ----

    private static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The DER-encoded public key is a fixed 12-byte prefix + the raw 32-byte key; strip the prefix. */
    private static String addressOf(PublicKey publicKey) {
        byte[] der = publicKey.getEncoded();
        byte[] raw = Arrays.copyOfRange(der, der.length - 32, der.length);
        return HexFormat.of().formatHex(raw);
    }

    private static String signHex(KeyPair keyPair, byte[] message) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(message);
            return HexFormat.of().formatHex(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signB64Url(KeyPair keyPair, byte[] message) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(message);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String buildToken(KeyPair keyPair, String address, long iatSeconds, long expSeconds) {
        String payloadJson = "{\"addr\":\"" + address + "\",\"iat\":" + iatSeconds + ",\"exp\":" + expSeconds + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signatureB64 = signB64Url(keyPair, payloadB64.getBytes(StandardCharsets.US_ASCII));
        return payloadB64 + "." + signatureB64;
    }

    // ---- canonicalMessage ----

    @Test
    void canonicalMessageIsDeterministic() {
        byte[] a = WalletSignature.canonicalMessage("addr", 1000L, "POST", "/wallet/api/claim", new byte[0]);
        byte[] b = WalletSignature.canonicalMessage("addr", 1000L, "POST", "/wallet/api/claim", new byte[0]);
        assertArrayEquals(a, b);
    }

    @Test
    void canonicalMessageChangesWithAnyField() {
        byte[] base = WalletSignature.canonicalMessage("addr", 1000L, "POST", "/path", "body".getBytes(StandardCharsets.UTF_8));
        assertFalse(Arrays.equals(base,
                WalletSignature.canonicalMessage("other", 1000L, "POST", "/path", "body".getBytes(StandardCharsets.UTF_8))));
        assertFalse(Arrays.equals(base,
                WalletSignature.canonicalMessage("addr", 2000L, "POST", "/path", "body".getBytes(StandardCharsets.UTF_8))));
        assertFalse(Arrays.equals(base,
                WalletSignature.canonicalMessage("addr", 1000L, "GET", "/path", "body".getBytes(StandardCharsets.UTF_8))));
        assertFalse(Arrays.equals(base,
                WalletSignature.canonicalMessage("addr", 1000L, "POST", "/other", "body".getBytes(StandardCharsets.UTF_8))));
        assertFalse(Arrays.equals(base,
                WalletSignature.canonicalMessage("addr", 1000L, "POST", "/path", "different".getBytes(StandardCharsets.UTF_8))));
    }

    // ---- verifyLive ----

    @Test
    void validLiveSignatureVerifies() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long now = 1_700_000_000_000L;
        byte[] body = "{\"to_user_id\":\"bob\",\"amount\":0.4}".getBytes(StandardCharsets.UTF_8);
        byte[] message = WalletSignature.canonicalMessage(address, now, "POST", "/wallet/api/transfer", body);
        String signature = signHex(keyPair, message);

        WalletSignature.AuthResult result = WalletSignature.verifyLive(
                address, signature, String.valueOf(now), "POST", "/wallet/api/transfer", body, now, SKEW_SECONDS);

        assertTrue(result.isValid());
        assertEquals(address, result.getAddress());
    }

    @Test
    void tamperingAnyFieldBreaksLiveVerification() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long now = 1_700_000_000_000L;
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        byte[] message = WalletSignature.canonicalMessage(address, now, "POST", "/wallet/api/claim", body);
        String signature = signHex(keyPair, message);

        assertFalse(WalletSignature.verifyLive(address, signature, String.valueOf(now), "POST",
                "/wallet/api/DIFFERENT", body, now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive(address, signature, String.valueOf(now + 1), "POST",
                "/wallet/api/claim", body, now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive(address, signature, String.valueOf(now), "GET",
                "/wallet/api/claim", body, now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive(address, signature, String.valueOf(now), "POST",
                "/wallet/api/claim", "{\"x\":1}".getBytes(StandardCharsets.UTF_8), now, SKEW_SECONDS).isValid());

        KeyPair otherKeyPair = generateKeyPair();
        String otherAddress = addressOf(otherKeyPair.getPublic());
        assertFalse(WalletSignature.verifyLive(otherAddress, signature, String.valueOf(now), "POST",
                "/wallet/api/claim", body, now, SKEW_SECONDS).isValid());
    }

    @Test
    void timestampOutsideSkewWindowIsRejected() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long now = 1_700_000_000_000L;
        long staleTimestamp = now - (SKEW_SECONDS + 10) * 1000L;
        byte[] body = new byte[0];
        byte[] message = WalletSignature.canonicalMessage(address, staleTimestamp, "POST", "/wallet/api/claim", body);
        String signature = signHex(keyPair, message);

        WalletSignature.AuthResult result = WalletSignature.verifyLive(
                address, signature, String.valueOf(staleTimestamp), "POST", "/wallet/api/claim", body, now, SKEW_SECONDS);
        assertFalse(result.isValid());
        assertEquals("X-Api-Timestamp outside allowed clock skew", result.getFailureReason());
    }

    @Test
    void timestampWithinSkewWindowIsAccepted() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long now = 1_700_000_000_000L;
        long recentTimestamp = now - (SKEW_SECONDS - 10) * 1000L;
        byte[] body = new byte[0];
        byte[] message = WalletSignature.canonicalMessage(address, recentTimestamp, "POST", "/wallet/api/claim", body);
        String signature = signHex(keyPair, message);

        assertTrue(WalletSignature.verifyLive(address, signature, String.valueOf(recentTimestamp), "POST",
                "/wallet/api/claim", body, now, SKEW_SECONDS).isValid());
    }

    @Test
    void malformedAddressOrSignatureHexIsRejectedCleanly() {
        long now = 1_700_000_000_000L;
        assertFalse(WalletSignature.verifyLive("not-hex", "a".repeat(128), String.valueOf(now), "POST",
                "/x", new byte[0], now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive("a".repeat(63), "a".repeat(128), String.valueOf(now), "POST",
                "/x", new byte[0], now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive("a".repeat(64), "not-hex-either", String.valueOf(now), "POST",
                "/x", new byte[0], now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive("a".repeat(64), "a".repeat(127), String.valueOf(now), "POST",
                "/x", new byte[0], now, SKEW_SECONDS).isValid());
        assertFalse(WalletSignature.verifyLive(null, "a".repeat(128), String.valueOf(now), "POST",
                "/x", new byte[0], now, SKEW_SECONDS).isValid());
    }

    // ---- verifyToken ----

    @Test
    void validTokenVerifies() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long nowSeconds = 1_700_000_000L;
        String token = buildToken(keyPair, address, nowSeconds, nowSeconds + 86_400);

        WalletSignature.AuthResult result = WalletSignature.verifyToken(token, nowSeconds * 1000L, null);
        assertTrue(result.isValid());
        assertEquals(address, result.getAddress());
    }

    @Test
    void expiredTokenIsRejected() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long nowSeconds = 1_700_000_000L;
        String token = buildToken(keyPair, address, nowSeconds - 100, nowSeconds - 1);

        WalletSignature.AuthResult result = WalletSignature.verifyToken(token, nowSeconds * 1000L, null);
        assertFalse(result.isValid());
        assertEquals("token expired", result.getFailureReason());
    }

    @Test
    void tamperedTokenPayloadIsRejected() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long nowSeconds = 1_700_000_000L;
        String token = buildToken(keyPair, address, nowSeconds, nowSeconds + 86_400);

        int dot = token.indexOf('.');
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"addr\":\"" + address + "\",\"iat\":" + nowSeconds + ",\"exp\":" + (nowSeconds + 999_999) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String tamperedToken = tamperedPayload + token.substring(dot);

        assertFalse(WalletSignature.verifyToken(tamperedToken, nowSeconds * 1000L, null).isValid());
    }

    @Test
    void tokenSignedByWrongKeyIsRejected() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        KeyPair otherKeyPair = generateKeyPair();
        long nowSeconds = 1_700_000_000L;
        // Sign with a different key than the one whose address is embedded in the payload.
        String token = buildToken(otherKeyPair, address, nowSeconds, nowSeconds + 86_400);

        assertFalse(WalletSignature.verifyToken(token, nowSeconds * 1000L, null).isValid());
    }

    @Test
    void malformedTokenFormatsAreRejectedCleanly() {
        long now = 1_700_000_000_000L;
        assertFalse(WalletSignature.verifyToken(null, now, null).isValid());
        assertFalse(WalletSignature.verifyToken("no-dot-here", now, null).isValid());
        assertFalse(WalletSignature.verifyToken("trailing.", now, null).isValid());
        assertFalse(WalletSignature.verifyToken("not-base64url!.also-not-base64!", now, null).isValid());
    }

    @Test
    void tokenIssuedBeforeRevocationTimestampIsRejected() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long nowSeconds = 1_700_000_000L;
        long issuedAt = nowSeconds - 1000;
        String token = buildToken(keyPair, address, issuedAt, nowSeconds + 86_400);

        long revokedBeforeMillis = (issuedAt + 1) * 1000L;
        WalletSignature.AuthResult result = WalletSignature.verifyToken(token, nowSeconds * 1000L, revokedBeforeMillis);
        assertFalse(result.isValid());
        assertEquals("token revoked", result.getFailureReason());
    }

    @Test
    void tokenIssuedAfterRevocationTimestampIsAccepted() {
        KeyPair keyPair = generateKeyPair();
        String address = addressOf(keyPair.getPublic());
        long nowSeconds = 1_700_000_000L;
        long issuedAt = nowSeconds - 10;
        String token = buildToken(keyPair, address, issuedAt, nowSeconds + 86_400);

        long revokedBeforeMillis = (issuedAt - 1) * 1000L;
        assertTrue(WalletSignature.verifyToken(token, nowSeconds * 1000L, revokedBeforeMillis).isValid());
    }
}
