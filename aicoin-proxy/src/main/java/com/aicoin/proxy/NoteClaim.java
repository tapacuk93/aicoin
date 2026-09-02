package com.aicoin.proxy;

/**
 * A payer's claim: "this note is for that wallet, and this nonce is the one they gave me".
 *
 * <p>Per CONTRACT.md's "Claimed notes". A bearer note is redeemed by whoever holds the string —
 * which means whoever <em>sees</em> it, since `note pay` prints it to a screen. A claim closes
 * that: redemption requires the note plus a signature, made by the note's issuer, over the
 * redeemer's own address and a nonce the redeemer chose. Photographing somebody else's note gets
 * you a string that will not redeem for you.
 *
 * <p>Neither side can produce a claim alone, which is the point of it:
 * <ul>
 *   <li>The receiver has a nonce and an address, and no way to make the issuer's signature.</li>
 *   <li>The payer has the key, and cannot guess a nonce they were never given — so they cannot
 *       prepare a payment for somebody they have not met, and a claim they made for one person is
 *       useless to any other.</li>
 * </ul>
 *
 * <p>And the pair of them is evidence. Two valid claims on one note, naming two payees, are two
 * statements signed by the payer that cannot both be honest — a proof of double-spending that
 * anybody can check and nobody can forge, which the ledger hands to the loser rather than merely
 * telling them they lost.
 */
final class NoteClaim {

    /**
     * The exact bytes a payer signs. Prefixed and newline-separated so a claim can never be
     * mistaken for — or replayed as — any other signed message this system uses.
     */
    static String message(String noteId, String payee, String nonce) {
        return "aicoin-claim\n" + noteId + "\n" + payee + "\n" + nonce;
    }

    /** Whether {@code issuer} really signed this note over to {@code payee} with this nonce. */
    static boolean verify(String issuer, String noteId, String payee, String nonce, String signatureHex) {
        if (issuer == null || noteId == null || payee == null || nonce == null || signatureHex == null) {
            return false;
        }
        if (!payee.matches("[0-9a-f]{64}") || nonce.isEmpty() || nonce.length() > 128) {
            return false;
        }
        return WalletSignature.signedBy(issuer, message(noteId, payee, nonce), signatureHex);
    }

    private NoteClaim() {
    }
}
