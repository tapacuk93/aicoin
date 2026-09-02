package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * A claim binds a note to the one wallet it was handed to. What these check is the property that
 * makes it worth having: neither side can produce one alone.
 */
class NoteClaimTest {

    private static final String NOTE_ID = "a".repeat(64);
    private static final String PAYEE = "b".repeat(64);
    private static final String NONCE = "c9f2e1";

    private static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String address(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        return HexFormat.of().formatHex(encoded, encoded.length - 32, encoded.length);
    }

    private static String sign(KeyPair pair, String message) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(pair.getPrivate());
        signature.update(message.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(signature.sign());
    }

    @Test
    void theIssuerCanSignANoteOverToOnePerson() throws Exception {
        KeyPair issuer = keys();
        String claim = sign(issuer, NoteClaim.message(NOTE_ID, PAYEE, NONCE));
        assertTrue(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, NONCE, claim));
    }

    @Test
    void aClaimIsUselessToAnybodyElse() throws Exception {
        // The whole point: somebody who photographs the note off a screen has the string and no
        // claim naming them, and a claim naming somebody else does not name them either.
        KeyPair issuer = keys();
        String claim = sign(issuer, NoteClaim.message(NOTE_ID, PAYEE, NONCE));

        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, "d".repeat(64), NONCE, claim),
                "a claim made out to one payee must not verify for another");
        assertFalse(NoteClaim.verify(address(issuer), "e".repeat(64), PAYEE, NONCE, claim),
                "nor for a different note");
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, "different-nonce", claim),
                "nor with a nonce the payee did not choose");
    }

    @Test
    void aReceiverCannotWriteTheirOwnClaim() throws Exception {
        // A receiver has an address and a nonce and no way to make the issuer's signature.
        KeyPair issuer = keys();
        KeyPair receiver = keys();
        String forged = sign(receiver, NoteClaim.message(NOTE_ID, PAYEE, NONCE));
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, NONCE, forged));
    }

    @Test
    void aClaimCannotBeReplayedAsAnyOtherSignedMessage() throws Exception {
        // Everything this system signs is prefixed, so a signature over one kind of message can
        // never be presented as another.
        assertTrue(NoteClaim.message(NOTE_ID, PAYEE, NONCE).startsWith("aicoin-claim\n"));
        KeyPair issuer = keys();
        String overRawFields = sign(issuer, NOTE_ID + "\n" + PAYEE + "\n" + NONCE);
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, NONCE, overRawFields));
    }

    @Test
    void rubbishIsRefusedRatherThanGuessedAt() throws Exception {
        KeyPair issuer = keys();
        String claim = sign(issuer, NoteClaim.message(NOTE_ID, PAYEE, NONCE));
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, "not-an-address", NONCE, claim));
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, "", claim));
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, "n".repeat(129), claim));
        assertFalse(NoteClaim.verify(address(issuer), NOTE_ID, PAYEE, NONCE, "not-hex"));
        assertFalse(NoteClaim.verify(null, NOTE_ID, PAYEE, NONCE, claim));
    }
}
