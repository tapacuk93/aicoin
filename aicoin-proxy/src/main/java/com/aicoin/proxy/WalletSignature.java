package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

/**
 * Ed25519 wallet-address cryptography, per CONTRACT.md's auth sections: a
 * wallet's address is the hex-encoded raw 32-byte Ed25519 public key. Two
 * independent verification schemes share the same address-reconstruction
 * and raw-signature-check primitives:
 *
 * <ul>
 *   <li><b>Live signatures</b> ({@link #verifyLive}) — wallet-management
 *   actions (claim/transfer/revoke-tokens) signed per-request with the
 *   private key, which only ever lives in the browser wallet page.</li>
 *   <li><b>API tokens</b> ({@link #verifyToken}) — a self-contained,
 *   expiring, signed credential issued client-side once and reused as a
 *   plain bearer value for AI-proxy calls, so routine API traffic never
 *   needs live per-request signing.</li>
 * </ul>
 *
 * Pure logic, no I/O — unit-testable without a live Redis connection or
 * Netty server; the one caller-supplied piece of state each scheme needs
 * (current time, and for tokens, the address's revoked-before timestamp)
 * is passed in rather than read here.
 */
final class WalletSignature {

    /**
     * The fixed 12-byte X.509 SubjectPublicKeyInfo DER prefix for an
     * Ed25519 public key per RFC 8410: SEQUENCE { SEQUENCE { OID
     * 1.3.101.112 }, BIT STRING (0 unused bits) &lt;32 raw key bytes&gt; }.
     * Prepending this to a raw 32-byte key yields a 44-byte DER blob
     * {@link X509EncodedKeySpec} can parse.
     */
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int ADDRESS_HEX_LENGTH = 64;
    private static final int SIGNATURE_HEX_LENGTH = 128;

    private WalletSignature() {
    }

    // ---- Live signatures: claim / transfer / revoke-tokens ----

    /**
     * {@code address + "\n" + timestampMillis + "\n" + method + "\n" + path + "\n" + hex(sha256(body))}
     * — the exact bytes the wallet page signs and this class re-derives to
     * verify against. Client and server must agree byte-for-byte on
     * {@code method}/{@code path} normalization or every signature breaks
     * silently; {@code path} is the same no-query-string path
     * {@code ProxyFrontendHandler.pathOnly()} extracts.
     */
    static byte[] canonicalMessage(String address, long timestampMillis, String method, String path, byte[] body) {
        String bodyHashHex = HexFormat.of().formatHex(sha256(body));
        String message = address + "\n" + timestampMillis + "\n" + method + "\n" + path + "\n" + bodyHashHex;
        return message.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifies a live-signed wallet-management request. Checks, in order:
     * headers present, address is 64 hex chars, timestamp parses and is
     * within {@code skewSeconds} of {@code nowMillis}, address decodes to
     * a valid Ed25519 key, and the signature verifies against the
     * canonical message built from the given request details. Every
     * failure path returns a specific reason and never throws.
     */
    static AuthResult verifyLive(String address, String signatureHex, String timestampHeader,
                                  String method, String path, byte[] body,
                                  long nowMillis, int skewSeconds) {
        if (address == null || signatureHex == null || timestampHeader == null) {
            return AuthResult.failure("missing X-Api-Key/X-Api-Signature/X-Api-Timestamp");
        }
        if (!isValidHex(address, ADDRESS_HEX_LENGTH)) {
            return AuthResult.failure("invalid X-Api-Key (must be 64 hex chars)");
        }
        long timestampMillis;
        try {
            timestampMillis = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return AuthResult.failure("invalid X-Api-Timestamp");
        }
        if (Math.abs(nowMillis - timestampMillis) > skewSeconds * 1000L) {
            return AuthResult.failure("X-Api-Timestamp outside allowed clock skew");
        }
        Optional<PublicKey> publicKey = parsePublicKey(address);
        if (!publicKey.isPresent()) {
            return AuthResult.failure("invalid X-Api-Key");
        }
        byte[] message = canonicalMessage(address, timestampMillis, method, path, body);
        if (!verifyRawSignature(publicKey.get(), message, signatureHex)) {
            return AuthResult.failure("signature verification failed");
        }
        return AuthResult.success(address);
    }

    // ---- API tokens: AI-proxy calls ----

    /**
     * Extracts the address embedded in a token's payload <b>without</b>
     * verifying its signature. Only safe use: deciding which address's
     * revocation record to fetch from Redis before calling {@link
     * #verifyToken} — never treat a peeked address as authenticated.
     */
    static Optional<String> peekTokenAddress(String token) {
        Optional<String> payloadPart = payloadPartOf(token);
        if (!payloadPart.isPresent()) {
            return Optional.empty();
        }
        Optional<Map<?, ?>> payload = decodePayload(payloadPart.get());
        if (!payload.isPresent()) {
            return Optional.empty();
        }
        Object addr = payload.get().get("addr");
        return (addr instanceof String && isValidHex((String) addr, ADDRESS_HEX_LENGTH))
                ? Optional.of((String) addr) : Optional.empty();
    }

    /**
     * Verifies a token of the form {@code base64url(payloadJson) + "." +
     * base64url(signature)}, where the payload is {@code
     * {"addr":"<64-hex>","iat":<epochSeconds>,"exp":<epochSeconds>}} and
     * the signature covers the exact base64url-encoded payload string
     * bytes (JWT-style: sign the encoded form, not the raw JSON, so
     * there's no key-ordering/whitespace ambiguity). Rejects malformed
     * tokens, signature mismatches, expiry, and — if {@code
     * revokedBeforeMillis} is non-null — any token issued at or before
     * that timestamp.
     */
    static AuthResult verifyToken(String token, long nowMillis, Long revokedBeforeMillis) {
        Optional<String> payloadPartOpt = payloadPartOf(token);
        if (!payloadPartOpt.isPresent()) {
            return AuthResult.failure(token == null ? "missing X-Api-Key" : "invalid token format");
        }
        String payloadPart = payloadPartOpt.get();
        String signaturePart = token.substring(payloadPart.length() + 1);

        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getUrlDecoder().decode(signaturePart);
        } catch (IllegalArgumentException e) {
            return AuthResult.failure("invalid token encoding");
        }
        if (signatureBytes.length != 64) {
            return AuthResult.failure("invalid token signature length");
        }

        Optional<Map<?, ?>> payloadOpt = decodePayload(payloadPart);
        if (!payloadOpt.isPresent()) {
            return AuthResult.failure("invalid token payload");
        }
        Map<?, ?> payload = payloadOpt.get();
        Object addr = payload.get("addr");
        Object iat = payload.get("iat");
        Object exp = payload.get("exp");
        if (!(addr instanceof String) || !isValidHex((String) addr, ADDRESS_HEX_LENGTH)
                || !(iat instanceof Number) || !(exp instanceof Number)) {
            return AuthResult.failure("invalid token payload fields");
        }
        String address = (String) addr;
        long iatSeconds = ((Number) iat).longValue();
        long expSeconds = ((Number) exp).longValue();

        Optional<PublicKey> publicKey = parsePublicKey(address);
        if (!publicKey.isPresent()) {
            return AuthResult.failure("invalid token address");
        }
        boolean signatureOk;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey.get());
            sig.update(payloadPart.getBytes(StandardCharsets.US_ASCII));
            signatureOk = sig.verify(signatureBytes);
        } catch (Exception e) {
            return AuthResult.failure("token signature verification error");
        }
        if (!signatureOk) {
            return AuthResult.failure("token signature verification failed");
        }
        if (expSeconds * 1000L <= nowMillis) {
            return AuthResult.failure("token expired");
        }
        if (revokedBeforeMillis != null && iatSeconds * 1000L <= revokedBeforeMillis) {
            return AuthResult.failure("token revoked");
        }
        return AuthResult.success(address);
    }

    // ---- shared primitives ----

    private static Optional<String> payloadPartOf(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(token.substring(0, dot));
    }

    private static Optional<Map<?, ?>> decodePayload(String payloadPart) {
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadPart);
            Object parsed = new Yaml().load(new String(payloadBytes, StandardCharsets.UTF_8));
            return (parsed instanceof Map) ? Optional.of((Map<?, ?>) parsed) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Whether {@code address} signed {@code message} — the same Ed25519 check the live-signature
     * path makes, exposed for the one other place a wallet's signature has to be checked against
     * its address: a payer's claim binding a note to the person they handed it to.
     */
    static boolean signedBy(String address, String message, String signatureHex) {
        Optional<PublicKey> key = parsePublicKey(address);
        return key.isPresent()
                && verifyRawSignature(key.get(), message.getBytes(java.nio.charset.StandardCharsets.UTF_8), signatureHex);
    }

    private static Optional<PublicKey> parsePublicKey(String addressHex) {
        if (!isValidHex(addressHex, ADDRESS_HEX_LENGTH)) {
            return Optional.empty();
        }
        try {
            byte[] raw = HexFormat.of().parseHex(addressHex);
            byte[] der = new byte[ED25519_X509_PREFIX.length + raw.length];
            System.arraycopy(ED25519_X509_PREFIX, 0, der, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(raw, 0, der, ED25519_X509_PREFIX.length, raw.length);
            return Optional.of(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean verifyRawSignature(PublicKey publicKey, byte[] message, String signatureHex) {
        if (!isValidHex(signatureHex, SIGNATURE_HEX_LENGTH)) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(message);
            return sig.verify(HexFormat.of().parseHex(signatureHex));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean isValidHex(String s, int expectedLength) {
        if (s == null || s.length() != expectedLength) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isHexChar = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHexChar) {
                return false;
            }
        }
        return true;
    }

    /** Outcome of {@link #verifyLive} or {@link #verifyToken}: either a verified address, or a specific failure reason. */
    static final class AuthResult {
        private final boolean valid;
        private final String address;
        private final String failureReason;

        private AuthResult(boolean valid, String address, String failureReason) {
            this.valid = valid;
            this.address = address;
            this.failureReason = failureReason;
        }

        static AuthResult success(String address) {
            return new AuthResult(true, address, null);
        }

        static AuthResult failure(String reason) {
            return new AuthResult(false, null, reason);
        }

        boolean isValid() {
            return valid;
        }

        /** The verified wallet address; only meaningful when {@link #isValid()} is true. */
        String getAddress() {
            return address;
        }

        /** A specific, safe-to-return-to-the-client reason; only meaningful when {@link #isValid()} is false. */
        String getFailureReason() {
            return failureReason;
        }
    }
}
