package com.aicoin.proxy;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The ledger's own Ed25519 key, used for one thing: signing bearer notes so a receiver can check
 * one offline.
 *
 * <p>It is generated once and kept in the ledger, rather than configured, because it is not a
 * secret that protects anything a Redis-level attacker could not already do — somebody who can
 * write to the ledger can write balances, which is strictly worse than forging notes. Keeping it
 * there means notes issued before a restart still verify after one.
 *
 * <p>Rotating it invalidates the <em>offline</em> check on notes already out — they still redeem,
 * because redemption asks the ledger what it issued rather than trusting the note.
 */
final class NoteSigner {

    private static final Logger LOG = Logger.getLogger(NoteSigner.class.getName());
    private static final AtomicReference<NoteSigner> CACHED = new AtomicReference<>();

    private final KeyPair keyPair;

    private NoteSigner(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    /**
     * Hands the signer to {@code onReady}, loading or creating the key on first use. Async because
     * everything the ledger does is: the alternative is blocking a Netty thread on Redis at
     * startup, for a key most deployments will never use.
     */
    static void ensure(AicoinLedger ledger, Consumer<Optional<NoteSigner>> onReady) {
        NoteSigner cached = CACHED.get();
        if (cached != null) {
            onReady.accept(Optional.of(cached));
            return;
        }
        ledger.noteSigningKey(stored -> {
            if (!stored.isPresent()) {
                onReady.accept(Optional.empty());
                return;
            }
            try {
                NoteSigner signer = fromStored(stored.get());
                CACHED.compareAndSet(null, signer);
                onReady.accept(Optional.of(CACHED.get()));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "note signing key is unusable", e);
                onReady.accept(Optional.empty());
            }
        });
    }

    /** A fresh keypair, encoded as {@code <pkcs8 hex>:<x509 hex>} for the ledger to hold. */
    static String generateStored() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        return hex(pair.getPrivate().getEncoded()) + ":" + hex(pair.getPublic().getEncoded());
    }

    private static NoteSigner fromStored(String stored) throws Exception {
        String[] halves = stored.split(":", 2);
        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(unhex(halves[0])));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(unhex(halves[1])));
        return new NoteSigner(new KeyPair(publicKey, privateKey));
    }

    byte[] sign(String encodedPayload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(encodedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return signature.sign();
    }

    /**
     * The raw 32-byte public key, hex-encoded — the form a client verifies with. The X.509 encoding
     * wraps it in a fixed 12-byte prefix (RFC 8410); everything after that is the key itself.
     */
    String publicKeyHex() {
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        return hex(raw);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
