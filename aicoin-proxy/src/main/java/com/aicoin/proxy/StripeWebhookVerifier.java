package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies Stripe's {@code Stripe-Signature} header on an incoming webhook, per Stripe's documented
 * scheme, as a pure function so it can be tested without a live Stripe account or a socket.
 *
 * <p>This is the whole security boundary of the card path. {@code POST /checkout/webhook} is a
 * public, unauthenticated endpoint that credits a wallet — anyone on the internet can post to it,
 * and the only thing separating a real payment from a forged one is the HMAC below. The wallet
 * address travels inside the event's metadata, so a forgery that verified would mint coins into an
 * attacker's own wallet at will.
 *
 * <p>The header looks like {@code t=1699999999,v1=<hex>,v1=<hex>} — more than one {@code v1} while
 * a signing secret is being rotated. The signed payload is {@code "<t>.<raw body>"}, and the raw
 * body means exactly the bytes received: re-serializing parsed JSON changes whitespace and key
 * order and would fail every time.
 *
 * <p>Two checks, both required:
 * <ul>
 *   <li><b>The HMAC</b>, compared with {@link MessageDigest#isEqual} rather than {@code equals} so
 *       the comparison does not return early on the first differing byte.
 *   <li><b>The timestamp</b>, within a tolerance. Without it a signature stays valid forever, and
 *       anyone who ever observed one genuine webhook body could replay it indefinitely. Idempotency
 *       on the session id blunts that, but the tolerance is what actually bounds it.
 * </ul>
 */
public final class StripeWebhookVerifier {

    /** Stripe's own default replay window. */
    public static final long DEFAULT_TOLERANCE_SECONDS = 300;

    private StripeWebhookVerifier() {
    }

    /**
     * @param header      the raw {@code Stripe-Signature} header value
     * @param payload     the raw request body, exactly as received
     * @param secret      the endpoint's signing secret ({@code whsec_...})
     * @param nowSeconds  current epoch seconds
     * @param toleranceSeconds maximum age; a non-positive value disables the age check
     * @return true only if a signature matches and the timestamp is within tolerance
     */
    public static boolean verify(String header, byte[] payload, String secret,
                                 long nowSeconds, long toleranceSeconds) {
        if (header == null || payload == null || secret == null || secret.isEmpty()) {
            return false;
        }

        Long timestamp = null;
        boolean anySignature = false;
        boolean matched = false;

        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("t".equals(name)) {
                try {
                    timestamp = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if ("v1".equals(name)) {
                anySignature = true;
            }
        }
        if (timestamp == null || !anySignature) {
            return false;
        }
        if (toleranceSeconds > 0 && Math.abs(nowSeconds - timestamp) > toleranceSeconds) {
            return false;
        }

        byte[] expected = hmacSha256(secret, (timestamp + ".").getBytes(StandardCharsets.UTF_8), payload);
        if (expected == null) {
            return false;
        }
        // Every candidate is checked even after one matches, so the work done does not depend on
        // which signature was the right one.
        for (String part : header.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0 || !"v1".equals(part.substring(0, eq).trim())) {
                continue;
            }
            byte[] candidate = decodeHex(part.substring(eq + 1).trim());
            if (candidate != null && MessageDigest.isEqual(expected, candidate)) {
                matched = true;
            }
        }
        return matched;
    }

    /** Convenience overload using {@link #DEFAULT_TOLERANCE_SECONDS}. */
    public static boolean verify(String header, byte[] payload, String secret, long nowSeconds) {
        return verify(header, payload, secret, nowSeconds, DEFAULT_TOLERANCE_SECONDS);
    }

    private static byte[] hmacSha256(String secret, byte[] prefix, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(prefix);
            mac.update(payload);
            return mac.doFinal();
        } catch (Exception e) {
            return null;
        }
    }

    /** Null for anything that is not clean lowercase-or-uppercase hex of even length. */
    static byte[] decodeHex(String hex) {
        if (hex == null || hex.isEmpty() || hex.length() % 2 != 0) {
            return null;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
