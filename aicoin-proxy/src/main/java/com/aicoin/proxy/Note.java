package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

/**
 * A bearer note: coins taken out of a wallet and put into a string, so they can change hands with
 * no network between the two people doing it. Per CONTRACT.md's "Offline notes".
 *
 * <p>The string is <code>base64url(payload).base64url(signature)</code> — the same shape as an API
 * token, for the same reason: it verifies itself. The payload names the amount, the issuer and an
 * expiry; the signature is the ledger's, over the encoded payload, so a receiver holding the
 * ledger's public key can check <em>offline</em> that the note is genuine and worth what it says.
 *
 * <p>What offline verification cannot tell anyone is whether the note is still unspent. Nothing
 * can: that is a fact about the ledger, and the ledger is not there. Two things make that
 * survivable rather than fatal. The coins leave the issuer at issue, so the issuer cannot spend
 * them again while the note is out. And redemption is first-come — if the same note is handed to
 * two people, exactly one of them ends up with the coins, and the other is told plainly that it was
 * already redeemed.
 *
 * <p>The secret is the note's id, and the ledger stores only its hash. A dump of the database
 * redeems nothing.
 */
final class Note {

    /** Bytes of randomness in a note id. 32 is the same order as the wallet keys this rides beside. */
    private static final int ID_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String id;
    private final double amount;
    private final String issuer;
    private final long expiresAtSeconds;
    /** The only wallet that may redeem this note, or empty for a bearer note anyone can. */
    private final String payee;

    Note(String id, double amount, String issuer, long expiresAtSeconds, String payee) {
        this.id = id;
        this.amount = amount;
        this.issuer = issuer;
        this.expiresAtSeconds = expiresAtSeconds;
        this.payee = payee == null ? "" : payee;
    }

    /** A fresh note id: {@value #ID_BYTES} bytes from {@link SecureRandom}, hex-encoded. */
    static Note mint(double amount, String issuer, long expiresAtSeconds, String payee) {
        byte[] raw = new byte[ID_BYTES];
        RANDOM.nextBytes(raw);
        return new Note(hex(raw), amount, issuer, expiresAtSeconds, payee);
    }

    String getId() {
        return id;
    }

    double getAmount() {
        return amount;
    }

    String getIssuer() {
        return issuer;
    }

    long getExpiresAtSeconds() {
        return expiresAtSeconds;
    }

    /** Empty for a bearer note; otherwise the one wallet that can redeem this. */
    String getPayee() {
        return payee;
    }

    /** What the ledger is keyed by: the hash of the secret, never the secret. */
    String hash() {
        return hashOf(id);
    }

    static String hashOf(String noteId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(noteId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /**
     * Six hex characters of the note's hash, in three pairs: the thing two people read aloud to
     * each other to confirm the note arrived as it left. Short enough to compare on a screen, and
     * derived from the hash rather than the id, so saying it out loud gives nothing away.
     */
    String fingerprint() {
        String hash = hash();
        return (hash.substring(0, 2) + "-" + hash.substring(2, 4) + "-" + hash.substring(4, 6))
                .toUpperCase(java.util.Locale.ROOT);
    }

    String payloadJson() {
        return "{\"v\":1,\"id\":\"" + id + "\",\"amt\":" + formatAmount(amount)
                + ",\"iss\":\"" + issuer + "\",\"exp\":" + expiresAtSeconds
                + ",\"pay\":\"" + payee + "\"}";
    }

    String encodedPayload() {
        return ENCODER.encodeToString(payloadJson().getBytes(StandardCharsets.UTF_8));
    }

    /** The whole note, ready to be shown, scanned or read out. */
    String encode(byte[] signature) {
        return encodedPayload() + "." + ENCODER.encodeToString(signature);
    }

    /**
     * Reads a note string back. Does <em>not</em> check the signature — that is the holder's job,
     * with the ledger's public key; on this side the id is the authority, since the ledger looks up
     * what it issued rather than trusting what it is handed.
     */
    static Optional<Note> decode(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        String trimmed = encoded.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(trimmed.substring(0, dot));
            Object parsed = new Yaml().load(new String(payload, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                return Optional.empty();
            }
            Map<?, ?> fields = (Map<?, ?>) parsed;
            Object id = fields.get("id");
            Object amount = fields.get("amt");
            Object issuer = fields.get("iss");
            Object expiry = fields.get("exp");
            if (!(id instanceof String) || !(amount instanceof Number) || !(issuer instanceof String)
                    || !(expiry instanceof Number)) {
                return Optional.empty();
            }
            Object payee = fields.get("pay");
            return Optional.of(new Note((String) id, ((Number) amount).doubleValue(),
                    (String) issuer, ((Number) expiry).longValue(),
                    payee instanceof String ? (String) payee : ""));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The signed part of a note string, for verifying it. */
    static Optional<String> encodedPayloadOf(String encoded) {
        int dot = encoded == null ? -1 : encoded.trim().indexOf('.');
        return dot > 0 ? Optional.of(encoded.trim().substring(0, dot)) : Optional.empty();
    }

    static Optional<byte[]> signatureOf(String encoded) {
        if (encoded == null) {
            return Optional.empty();
        }
        String trimmed = encoded.trim();
        int dot = trimmed.indexOf('.');
        if (dot <= 0 || dot == trimmed.length() - 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(DECODER.decode(trimmed.substring(dot + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Whole coins as whole numbers, so a note for 50 says 50 and not 50.0. */
    static String formatAmount(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
