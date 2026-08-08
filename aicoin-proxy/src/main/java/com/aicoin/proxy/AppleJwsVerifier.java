package com.aicoin.proxy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Verifies a StoreKit2 {@code Transaction.jwsRepresentation} — a JWS (JSON Web Signature, compact
 * serialization) whose {@code x5c} header carries the signing certificate chain, per CONTRACT.md's
 * "Redeeming a purchase" section. Trust comes entirely from that certificate chain rooting at
 * Apple's bundled Root CA - G3 — there is no shared secret and no network call needed, which is
 * exactly what makes this safe to verify synchronously, server-side, offline.
 *
 * <p>Pure logic (no Redis/network I/O) aside from reading the bundled root certificate resource;
 * both the trusted root and "now" are parameters so tests can substitute a genuinely-generated
 * test root/chain instead of a real Apple certificate — see {@code AppleJwsVerifierTest}.
 *
 * <p><b>TODO:</b> {@code src/main/resources/apple-root-ca-g3.der} is genuinely Apple's real,
 * publicly published "Apple Root CA - G3" certificate (fetched from
 * {@code https://www.apple.com/certificateauthority/AppleRootCA-G3.cer}, SHA-256 fingerprint
 * {@code 63:34:3A:BF:B8:9A:6A:03:EB:B5:7E:9B:3F:5F:A7:BE:7C:4F:5C:75:6F:30:17:B3:A8:C4:88:C3:65:3E:91:79},
 * matching Apple's own published fingerprint for this root) — not a placeholder. If this resource
 * is ever swapped out or regenerated, re-verify the fingerprint against Apple's published value at
 * that URL before trusting it; a wrong root here would make every real purchase fail verification
 * (fails closed, at least, rather than accepting forged transactions).
 */
final class AppleJwsVerifier {

    private static final String ROOT_CA_RESOURCE = "/apple-root-ca-g3.der";
    private static final String ES256_JCA_NAME = "SHA256withECDSA";

    private AppleJwsVerifier() {
    }

    /** Loads the bundled Apple Root CA - G3 certificate this proxy trusts as the anchor of every StoreKit2 JWS's {@code x5c} chain. */
    static X509Certificate loadAppleRootCa() {
        try (InputStream in = AppleJwsVerifier.class.getResourceAsStream(ROOT_CA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled Apple Root CA - G3 resource missing: " + ROOT_CA_RESOURCE);
            }
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException("could not load bundled Apple Root CA - G3", e);
        }
    }

    /** Verifies against the real, bundled Apple Root CA - G3. */
    static VerifyResult verify(String jws, long nowMillis) {
        return verify(jws, nowMillis, loadAppleRootCa());
    }

    /**
     * @param trustedRoot the root certificate the {@code x5c} chain must ultimately chain up to —
     *                     a parameter purely so tests can substitute a genuinely-generated test
     *                     root instead of the real Apple certificate.
     */
    static VerifyResult verify(String jws, long nowMillis, X509Certificate trustedRoot) {
        if (jws == null || jws.isEmpty()) {
            return VerifyResult.failure("missing signed_transaction");
        }
        String[] parts = jws.split("\\.", -1);
        if (parts.length != 3) {
            return VerifyResult.failure("malformed signed_transaction (expected JWS compact serialization)");
        }

        byte[] headerBytes;
        byte[] payloadBytes;
        byte[] signatureBytes;
        try {
            headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            signatureBytes = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return VerifyResult.failure("malformed signed_transaction encoding");
        }

        Map<?, ?> header;
        Map<?, ?> payload;
        try {
            Object headerParsed = new Yaml().load(new String(headerBytes, StandardCharsets.UTF_8));
            Object payloadParsed = new Yaml().load(new String(payloadBytes, StandardCharsets.UTF_8));
            if (!(headerParsed instanceof Map) || !(payloadParsed instanceof Map)) {
                return VerifyResult.failure("malformed signed_transaction JSON");
            }
            header = (Map<?, ?>) headerParsed;
            payload = (Map<?, ?>) payloadParsed;
        } catch (Exception e) {
            return VerifyResult.failure("malformed signed_transaction JSON");
        }

        if (!"ES256".equals(header.get("alg"))) {
            return VerifyResult.failure("unsupported JWS alg (only ES256 is supported)");
        }
        Object x5cRaw = header.get("x5c");
        if (!(x5cRaw instanceof List) || ((List<?>) x5cRaw).isEmpty()) {
            return VerifyResult.failure("missing x5c certificate chain");
        }

        List<X509Certificate> chain = new ArrayList<>();
        try {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            for (Object entry : (List<?>) x5cRaw) {
                byte[] der = Base64.getDecoder().decode(String.valueOf(entry));
                chain.add((X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (Exception e) {
            return VerifyResult.failure("malformed x5c certificate chain");
        }

        Date now = new Date(nowMillis);
        try {
            for (X509Certificate cert : chain) {
                cert.checkValidity(now);
            }
            trustedRoot.checkValidity(now);
        } catch (Exception e) {
            return VerifyResult.failure("certificate chain has expired or is not yet valid");
        }

        try {
            for (int i = 0; i < chain.size() - 1; i++) {
                chain.get(i).verify(chain.get(i + 1).getPublicKey());
            }
            chain.get(chain.size() - 1).verify(trustedRoot.getPublicKey());
        } catch (Exception e) {
            return VerifyResult.failure("certificate chain does not verify up to the trusted root");
        }

        try {
            Signature verifier = Signature.getInstance(ES256_JCA_NAME);
            verifier.initVerify(chain.get(0).getPublicKey());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(joseToDer(signatureBytes))) {
                return VerifyResult.failure("JWS signature verification failed");
            }
        } catch (Exception e) {
            return VerifyResult.failure("JWS signature verification error");
        }

        Object bundleId = payload.get("bundleId");
        Object productId = payload.get("productId");
        Object transactionId = payload.get("transactionId");
        if (!(bundleId instanceof String) || !(productId instanceof String) || !(transactionId instanceof String)) {
            return VerifyResult.failure("signed_transaction payload missing bundleId/productId/transactionId");
        }
        Object quantityRaw = payload.get("quantity");
        int quantity = (quantityRaw instanceof Number) ? Math.max(1, ((Number) quantityRaw).intValue()) : 1;

        return VerifyResult.success((String) bundleId, (String) productId, (String) transactionId, quantity);
    }

    // ---- RFC 7518 §3.4: ES256 JWS signatures are raw big-endian R||S (32+32 bytes), not ASN.1
    // DER — but the JCA "SHA256withECDSA" Signature.verify() expects DER. Convert. ----

    static byte[] joseToDer(byte[] joseSignature) {
        if (joseSignature.length != 64) {
            throw new IllegalArgumentException(
                    "ES256 JWS signature must be exactly 64 raw bytes, got " + joseSignature.length);
        }
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(joseSignature, 0, 32));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(joseSignature, 32, 64));
        return derSequence(derInteger(r), derInteger(s));
    }

    static byte[] derInteger(BigInteger value) {
        return derTlv(0x02, value.toByteArray());
    }

    static byte[] derSequence(byte[]... children) {
        int length = 0;
        for (byte[] child : children) {
            length += child.length;
        }
        byte[] content = new byte[length];
        int pos = 0;
        for (byte[] child : children) {
            System.arraycopy(child, 0, content, pos, child.length);
            pos += child.length;
        }
        return derTlv(0x30, content);
    }

    static byte[] derTlv(int tag, byte[] content) {
        byte[] length = derLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    static byte[] derLength(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        List<Byte> bytes = new ArrayList<>();
        int n = length;
        while (n > 0) {
            bytes.add(0, (byte) (n & 0xFF));
            n >>>= 8;
        }
        byte[] out = new byte[1 + bytes.size()];
        out[0] = (byte) (0x80 | bytes.size());
        for (int i = 0; i < bytes.size(); i++) {
            out[1 + i] = bytes.get(i);
        }
        return out;
    }

    /** Outcome of {@link #verify}: either the verified payload's identifying fields, or a specific failure reason. */
    static final class VerifyResult {
        private final boolean valid;
        private final String bundleId;
        private final String productId;
        private final String transactionId;
        private final int quantity;
        private final String failureReason;

        private VerifyResult(boolean valid, String bundleId, String productId, String transactionId,
                              int quantity, String failureReason) {
            this.valid = valid;
            this.bundleId = bundleId;
            this.productId = productId;
            this.transactionId = transactionId;
            this.quantity = quantity;
            this.failureReason = failureReason;
        }

        static VerifyResult success(String bundleId, String productId, String transactionId, int quantity) {
            return new VerifyResult(true, bundleId, productId, transactionId, quantity, null);
        }

        static VerifyResult failure(String reason) {
            return new VerifyResult(false, null, null, null, 0, reason);
        }

        boolean isValid() {
            return valid;
        }

        String getBundleId() {
            return bundleId;
        }

        String getProductId() {
            return productId;
        }

        String getTransactionId() {
            return transactionId;
        }

        int getQuantity() {
            return quantity;
        }

        String getFailureReason() {
            return failureReason;
        }
    }
}
