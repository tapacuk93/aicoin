package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The arithmetic behind a chain, per CONTRACT.md's "Chains": one secret in a wallet standing in for
 * a purse full of notes.
 *
 * <p>A wallet picks a seed and hashes it n times. Only the last hash — the tip — is given to the
 * ledger, and n coins leave the balance. Paying k coins means revealing the value k steps back from
 * the tip; anyone can check it by hashing forward k times and arriving at the tip they already had.
 *
 * <p>Two properties do the work, and both are just what a hash is:
 * <ul>
 *   <li><b>You cannot walk backwards.</b> Holding the tip, or any link, tells you nothing about the
 *       links still unspent behind it — so a receiver who was paid 3 coins cannot help themselves
 *       to the rest.</li>
 *   <li><b>You cannot find another seed that lands on the same tip.</b> That is a preimage search
 *       against SHA-256, and nobody is going to win it. Two wallets cannot end up with chains that
 *       are interchangeable, however many chains exist.</li>
 * </ul>
 *
 * <p>32 bytes of preloaded secret therefore covers a whole purse: the wallet holds one seed instead
 * of one signed note per coin, and each payment is a hash to verify rather than a signature.
 */
final class HashChain {

    private HashChain() {
    }

    /** Hashes {@code value} once. Chains are hex all the way down, so a link is the hex of a hash. */
    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    /** The tip of a chain of {@code links} hashes over {@code seed}. */
    static String tip(String seed, int links) {
        String value = seed;
        for (int i = 0; i < links; i++) {
            value = hash(value);
        }
        return value;
    }

    /**
     * Whether hashing {@code preimage} {@code steps} times arrives at {@code tip} — the whole
     * verification a receiver or the ledger does.
     *
     * <p>Bounded, because {@code steps} arrives from a request: an unbounded walk would be a way to
     * ask this process to hash for as long as somebody likes.
     */
    static boolean walksTo(String preimage, int steps, String tip) {
        if (preimage == null || tip == null || steps <= 0 || steps > MAX_LINKS) {
            return false;
        }
        String value = preimage;
        for (int i = 0; i < steps; i++) {
            value = hash(value);
        }
        return value.equals(tip);
    }

    /** Longest chain anybody may open, which is also the longest walk this will ever do. */
    static final int MAX_LINKS = 10_000;
}
