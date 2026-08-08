package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AppleJwsVerifier} against a <b>genuinely-generated</b> test certificate chain
 * and real EC signatures — same spirit as {@code WalletSignatureTest}'s real Ed25519 keypairs,
 * just for a StoreKit2-shaped JWS instead. No real Apple certificate material is used or
 * fabricated here; a self-signed test "root" plays the role {@code apple-root-ca-g3.der} plays in
 * production, passed explicitly via {@link AppleJwsVerifier#verify(String, long, X509Certificate)}.
 *
 * <p>Building a self-signed X.509 chain from scratch (no bouncycastle or other crypto library is a
 * dependency of this project) requires hand-encoding minimal ASN.1 DER — a v1 certificate
 * (omitting the version field and all v3 extensions, which {@link X509Certificate#verify} never
 * inspects) is sufficient here, since the SubjectPublicKeyInfo field can just reuse {@code
 * PublicKey.getEncoded()} verbatim. This test file's DER-building helpers are deliberately
 * separate from {@link AppleJwsVerifier}'s own (production only ever needs to *decode* real
 * Apple-issued certificates, never build one).
 */
class AppleJwsVerifierTest {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    // ---- test fixture: a genuine 2-level chain (root -> intermediate -> leaf) ----

    private static final class TestChain {
        final X509Certificate root;
        final X509Certificate intermediate;
        final X509Certificate leaf;
        final PrivateKey leafPrivateKey;

        TestChain(X509Certificate root, X509Certificate intermediate, X509Certificate leaf, PrivateKey leafPrivateKey) {
            this.root = root;
            this.intermediate = intermediate;
            this.leaf = leaf;
            this.leafPrivateKey = leafPrivateKey;
        }
    }

    private static KeyPair generateEcKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TestChain buildGenuineTestChain(Date leafNotBefore, Date leafNotAfter) {
        try {
            KeyPair rootKeyPair = generateEcKeyPair();
            KeyPair intermediateKeyPair = generateEcKeyPair();
            KeyPair leafKeyPair = generateEcKeyPair();

            Date farPast = new Date(System.currentTimeMillis() - 365 * DAY_MILLIS);
            Date farFuture = new Date(System.currentTimeMillis() + 365 * DAY_MILLIS);

            X509Certificate root = buildCertificate(1, "Test Root CA", rootKeyPair.getPublic(),
                    "Test Root CA", rootKeyPair.getPrivate(), farPast, farFuture);
            X509Certificate intermediate = buildCertificate(2, "Test Intermediate CA", intermediateKeyPair.getPublic(),
                    "Test Root CA", rootKeyPair.getPrivate(), farPast, farFuture);
            X509Certificate leaf = buildCertificate(3, "Test Leaf", leafKeyPair.getPublic(),
                    "Test Intermediate CA", intermediateKeyPair.getPrivate(), leafNotBefore, leafNotAfter);

            return new TestChain(root, intermediate, leaf, leafKeyPair.getPrivate());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TestChain buildGenuineTestChain() {
        Date notBefore = new Date(System.currentTimeMillis() - DAY_MILLIS);
        Date notAfter = new Date(System.currentTimeMillis() + 365 * DAY_MILLIS);
        return buildGenuineTestChain(notBefore, notAfter);
    }

    // ---- minimal hand-rolled X.509 v1 certificate builder (pure JDK, no extensions/version field) ----

    private static final byte[] ECDSA_WITH_SHA256_OID_TLV =
            {0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    private static final byte[] COMMON_NAME_OID_TLV = {0x06, 0x03, 0x55, 0x04, 0x03};

    private static X509Certificate buildCertificate(int serialNumber, String subjectCn, PublicKey subjectPublicKey,
                                                      String issuerCn, PrivateKey issuerPrivateKey,
                                                      Date notBefore, Date notAfter) throws Exception {
        byte[] algorithmIdentifier = AppleJwsVerifier.derSequence(ECDSA_WITH_SHA256_OID_TLV);
        byte[] tbsCertificate = AppleJwsVerifier.derSequence(
                AppleJwsVerifier.derInteger(BigInteger.valueOf(serialNumber)),
                algorithmIdentifier,
                nameOf(issuerCn),
                AppleJwsVerifier.derSequence(utcTime(notBefore), utcTime(notAfter)),
                nameOf(subjectCn),
                subjectPublicKey.getEncoded());

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(issuerPrivateKey);
        signer.update(tbsCertificate);
        byte[] signatureBytes = signer.sign();

        byte[] bitString = new byte[signatureBytes.length + 1]; // leading byte = 0 unused bits
        System.arraycopy(signatureBytes, 0, bitString, 1, signatureBytes.length);
        byte[] certificateDer = AppleJwsVerifier.derSequence(
                tbsCertificate, algorithmIdentifier, AppleJwsVerifier.derTlv(0x03, bitString));

        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificateDer));
    }

    private static byte[] nameOf(String commonName) {
        byte[] value = AppleJwsVerifier.derTlv(0x13, commonName.getBytes(StandardCharsets.US_ASCII)); // PrintableString
        byte[] attributeTypeAndValue = AppleJwsVerifier.derSequence(COMMON_NAME_OID_TLV, value);
        byte[] relativeDistinguishedName = AppleJwsVerifier.derTlv(0x31, attributeTypeAndValue); // SET
        return AppleJwsVerifier.derSequence(relativeDistinguishedName);
    }

    private static byte[] utcTime(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return AppleJwsVerifier.derTlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    // ---- test-only reverse of AppleJwsVerifier.joseToDer, to build a genuine ES256 JWS ----

    private static byte[] derToJoseSignature(byte[] der) throws Exception {
        int pos = 0;
        if ((der[pos++] & 0xFF) != 0x30) {
            throw new IllegalStateException("expected DER SEQUENCE");
        }
        pos = skipLength(der, pos);
        byte[] r = readDerIntegerContent(der, new int[] {pos});
        int afterR = pos + tlvTotalLength(der, pos);
        byte[] s = readDerIntegerContent(der, new int[] {afterR});
        byte[] out = new byte[64];
        copyRightAligned(r, out, 0, 32);
        copyRightAligned(s, out, 32, 32);
        return out;
    }

    private static int skipLength(byte[] data, int pos) {
        int len = data[pos++] & 0xFF;
        if ((len & 0x80) != 0) {
            pos += (len & 0x7F);
        }
        return pos;
    }

    private static int tlvTotalLength(byte[] data, int tagPos) {
        int pos = tagPos + 1;
        int len = data[pos++] & 0xFF;
        if ((len & 0x80) == 0) {
            return (pos - tagPos) + len;
        }
        int numBytes = len & 0x7F;
        int actualLen = 0;
        for (int i = 0; i < numBytes; i++) {
            actualLen = (actualLen << 8) | (data[pos++] & 0xFF);
        }
        return (pos - tagPos) + actualLen;
    }

    private static byte[] readDerIntegerContent(byte[] data, int[] posHolder) {
        int pos = posHolder[0];
        if ((data[pos++] & 0xFF) != 0x02) {
            throw new IllegalStateException("expected DER INTEGER");
        }
        int len = data[pos++] & 0xFF;
        if ((len & 0x80) != 0) {
            int numBytes = len & 0x7F;
            len = 0;
            for (int i = 0; i < numBytes; i++) {
                len = (len << 8) | (data[pos++] & 0xFF);
            }
        }
        byte[] content = Arrays.copyOfRange(data, pos, pos + len);
        posHolder[0] = pos + len;
        return content;
    }

    private static void copyRightAligned(byte[] src, byte[] dest, int destOffset, int fieldLength) {
        byte[] trimmed = src;
        int start = 0;
        while (trimmed.length - start > fieldLength && trimmed[start] == 0) {
            start++;
        }
        int length = trimmed.length - start;
        System.arraycopy(trimmed, start, dest, destOffset + (fieldLength - length), length);
    }

    // ---- JWS assembly ----

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String buildJws(TestChain chain, String payloadJson) throws Exception {
        String x5cLeaf = Base64.getEncoder().encodeToString(chain.leaf.getEncoded());
        String x5cIntermediate = Base64.getEncoder().encodeToString(chain.intermediate.getEncoded());
        String header = "{\"alg\":\"ES256\",\"x5c\":[\"" + x5cLeaf + "\",\"" + x5cIntermediate + "\"]}";

        String headerB64 = base64Url(header.getBytes(StandardCharsets.UTF_8));
        String payloadB64 = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = headerB64 + "." + payloadB64;

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(chain.leafPrivateKey);
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] derSignature = signer.sign();
        String signatureB64 = base64Url(derToJoseSignature(derSignature));

        return signingInput + "." + signatureB64;
    }

    private static final String SAMPLE_PAYLOAD = "{\"bundleId\":\"com.tarasmaslov.infiniteairadio\","
            + "\"productId\":\"com.tarasmaslov.infiniteairadio.aicoin.small\","
            + "\"transactionId\":\"1000000900000001\",\"quantity\":1}";

    // ---- tests ----

    @Test
    void genuineChainVerifiesAndExtractsPayloadFields() throws Exception {
        TestChain chain = buildGenuineTestChain();
        String jws = buildJws(chain, SAMPLE_PAYLOAD);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), chain.root);

        assertTrue(result.isValid(), result.isValid() ? "" : result.getFailureReason());
        assertEquals("com.tarasmaslov.infiniteairadio", result.getBundleId());
        assertEquals("com.tarasmaslov.infiniteairadio.aicoin.small", result.getProductId());
        assertEquals("1000000900000001", result.getTransactionId());
        assertEquals(1, result.getQuantity());
    }

    @Test
    void quantityDefaultsToOneWhenAbsent() throws Exception {
        TestChain chain = buildGenuineTestChain();
        String payload = "{\"bundleId\":\"com.tarasmaslov.learn-it\","
                + "\"productId\":\"com.tarasmaslov.learn-it.aicoin.large\",\"transactionId\":\"tx-1\"}";
        String jws = buildJws(chain, payload);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), chain.root);
        assertTrue(result.isValid());
        assertEquals(1, result.getQuantity());
    }

    @Test
    void quantityGreaterThanOneIsPropagated() throws Exception {
        TestChain chain = buildGenuineTestChain();
        String payload = "{\"bundleId\":\"com.tarasmaslov.alllanguageslearner\","
                + "\"productId\":\"com.tarasmaslov.alllanguageslearner.aicoin.medium\","
                + "\"transactionId\":\"tx-2\",\"quantity\":3}";
        String jws = buildJws(chain, payload);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), chain.root);
        assertTrue(result.isValid());
        assertEquals(3, result.getQuantity());
    }

    @Test
    void tamperedPayloadFailsVerification() throws Exception {
        TestChain chain = buildGenuineTestChain();
        String jws = buildJws(chain, SAMPLE_PAYLOAD);
        String[] parts = jws.split("\\.");
        String tamperedPayload = base64Url(SAMPLE_PAYLOAD.replace("small", "xl").getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + tamperedPayload + "." + parts[2];

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(tampered, System.currentTimeMillis(), chain.root);
        assertFalse(result.isValid());
        assertEquals("JWS signature verification failed", result.getFailureReason());
    }

    @Test
    void tamperedSignatureFailsVerification() throws Exception {
        TestChain chain = buildGenuineTestChain();
        String jws = buildJws(chain, SAMPLE_PAYLOAD);
        String[] parts = jws.split("\\.");
        byte[] sigBytes = Base64.getUrlDecoder().decode(parts[2]);
        sigBytes[0] ^= 0x01;
        String tampered = parts[0] + "." + parts[1] + "." + base64Url(sigBytes);

        assertFalse(AppleJwsVerifier.verify(tampered, System.currentTimeMillis(), chain.root).isValid());
    }

    @Test
    void chainNotRootedInTheGivenTrustedRootFailsVerification() throws Exception {
        TestChain chain = buildGenuineTestChain();
        TestChain unrelatedChain = buildGenuineTestChain();
        String jws = buildJws(chain, SAMPLE_PAYLOAD);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), unrelatedChain.root);
        assertFalse(result.isValid());
        assertEquals("certificate chain does not verify up to the trusted root", result.getFailureReason());
    }

    @Test
    void expiredLeafCertificateFailsVerification() throws Exception {
        Date notBefore = new Date(System.currentTimeMillis() - 30 * DAY_MILLIS);
        Date notAfter = new Date(System.currentTimeMillis() - 10 * DAY_MILLIS); // already expired
        TestChain chain = buildGenuineTestChain(notBefore, notAfter);
        String jws = buildJws(chain, SAMPLE_PAYLOAD);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), chain.root);
        assertFalse(result.isValid());
        assertEquals("certificate chain has expired or is not yet valid", result.getFailureReason());
    }

    @Test
    void notYetValidLeafCertificateFailsVerification() throws Exception {
        Date notBefore = new Date(System.currentTimeMillis() + 30 * DAY_MILLIS); // not valid yet
        Date notAfter = new Date(System.currentTimeMillis() + 60 * DAY_MILLIS);
        TestChain chain = buildGenuineTestChain(notBefore, notAfter);
        String jws = buildJws(chain, SAMPLE_PAYLOAD);

        assertFalse(AppleJwsVerifier.verify(jws, System.currentTimeMillis(), chain.root).isValid());
    }

    @Test
    void unsupportedAlgIsRejected() throws Exception {
        TestChain chain = buildGenuineTestChain();
        String x5cLeaf = Base64.getEncoder().encodeToString(chain.leaf.getEncoded());
        String header = "{\"alg\":\"RS256\",\"x5c\":[\"" + x5cLeaf + "\"]}";
        String jws = base64Url(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(SAMPLE_PAYLOAD.getBytes(StandardCharsets.UTF_8)) + "." + base64Url(new byte[64]);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), chain.root);
        assertFalse(result.isValid());
        assertEquals("unsupported JWS alg (only ES256 is supported)", result.getFailureReason());
    }

    @Test
    void malformedShapesAreRejectedCleanly() {
        long now = System.currentTimeMillis();
        assertFalse(AppleJwsVerifier.verify(null, now, AppleJwsVerifierTest.buildGenuineTestChain().root).isValid());
        assertFalse(AppleJwsVerifier.verify("", now, buildGenuineTestChain().root).isValid());
        assertFalse(AppleJwsVerifier.verify("only.two", now, buildGenuineTestChain().root).isValid());
        assertFalse(AppleJwsVerifier.verify("not-base64!.not-base64!.not-base64!", now, buildGenuineTestChain().root).isValid());
        assertFalse(AppleJwsVerifier.verify(base64Url("{}".getBytes(StandardCharsets.UTF_8)) + ".e30.e30",
                now, buildGenuineTestChain().root).isValid());
    }

    @Test
    void missingX5cIsRejected() {
        String header = base64Url("{\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(SAMPLE_PAYLOAD.getBytes(StandardCharsets.UTF_8));
        String jws = header + "." + payload + "." + base64Url(new byte[64]);

        AppleJwsVerifier.VerifyResult result = AppleJwsVerifier.verify(jws, System.currentTimeMillis(), buildGenuineTestChain().root);
        assertFalse(result.isValid());
        assertEquals("missing x5c certificate chain", result.getFailureReason());
    }

    @Test
    void loadAppleRootCaLoadsTheGenuineBundledResource() {
        X509Certificate root = AppleJwsVerifier.loadAppleRootCa();
        assertEquals("C=US,O=Apple Inc.,OU=Apple Certification Authority,CN=Apple Root CA - G3",
                root.getSubjectX500Principal().getName());
    }

    // ---- DER<->JOSE signature conversion round-trip sanity (both directions used across prod + this test rig) ----

    @Test
    void joseToDerAndBackRoundTrips() throws Exception {
        KeyPair keyPair = generateEcKeyPair();
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update("hello".getBytes(StandardCharsets.UTF_8));
        byte[] der = signer.sign();

        byte[] jose = derToJoseSignature(der);
        assertEquals(64, jose.length);
        byte[] roundTrippedDer = AppleJwsVerifier.joseToDer(jose);

        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update("hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(roundTrippedDer));
    }
}
