package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The bearer note's format. Two properties carry the whole design: the id is unguessable, and the
 * ledger stores only its hash — so the thing that spends the note exists in the holder's hands and
 * nowhere else, including here.
 */
class NoteTest {

    @Test
    void everyNoteGetsItsOwnUnguessableId() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            Note note = Note.mint(10, "a".repeat(64), 1_800_000_000L);
            assertEquals(64, note.getId().length(), "32 bytes of randomness, hex-encoded");
            assertTrue(seen.add(note.getId()), "ids must not repeat");
        }
    }

    @Test
    void theLedgerKeyIsTheHashSoADatabaseDumpRedeemsNothing() {
        Note note = Note.mint(10, "b".repeat(64), 1_800_000_000L);
        assertNotEquals(note.getId(), note.hash());
        assertEquals(64, note.hash().length());
        // Deterministic: a holder can ask after their note's state by hash, without handing over
        // the secret that would let anyone spend it.
        assertEquals(note.hash(), Note.hashOf(note.getId()));
    }

    @Test
    void aNoteSurvivesTheRoundTripItWillActuallyMake() {
        Note note = Note.mint(25.5, "c".repeat(64), 1_800_000_000L);
        String encoded = note.encode(new byte[64]);

        Optional<Note> decoded = Note.decode(encoded);
        assertTrue(decoded.isPresent());
        assertEquals(note.getId(), decoded.get().getId());
        assertEquals(25.5, decoded.get().getAmount(), 1e-9);
        assertEquals("c".repeat(64), decoded.get().getIssuer());
        assertEquals(1_800_000_000L, decoded.get().getExpiresAtSeconds());
    }

    @Test
    void whatIsSignedIsTheEncodedPayloadExactly() {
        // The receiver verifies offline against the string in front of them, so the bytes signed
        // and the bytes checked have to be the same bytes, with no re-encoding in between.
        Note note = Note.mint(5, "d".repeat(64), 1_800_000_000L);
        String encoded = note.encode(new byte[64]);
        assertEquals(note.encodedPayload(), Note.encodedPayloadOf(encoded).orElse(""));
        assertEquals(64, Note.signatureOf(encoded).orElse(new byte[0]).length);
    }

    @Test
    void rubbishIsRejectedRatherThanGuessedAt() {
        for (String bad : new String[] {"", "not-a-note", "onlyonepart", ".", "abc.", ".abc",
                "!!!.???", "eyJub3Rqc29uIjo=.abc"}) {
            assertFalse(Note.decode(bad).isPresent(), bad + " should not decode");
        }
    }

    @Test
    void theFingerprintIsShortReadableAndSaysNothing() {
        Note note = Note.mint(10, "e".repeat(64), 1_800_000_000L);
        String fingerprint = note.fingerprint();

        assertTrue(fingerprint.matches("[0-9A-F]{2}-[0-9A-F]{2}-[0-9A-F]{2}"),
                "two people read this to each other: " + fingerprint);
        // Derived from the hash, not the id — saying it aloud gives away nothing that could spend it.
        assertTrue(note.hash().toUpperCase(java.util.Locale.ROOT).startsWith(fingerprint.replace("-", "")));
        assertFalse(note.getId().toUpperCase(java.util.Locale.ROOT).startsWith(fingerprint.replace("-", "")));
    }

    @Test
    void wholeAmountsReadAsWholeNumbers() {
        assertEquals("50", Note.formatAmount(50));
        assertEquals("0.5", Note.formatAmount(0.5));
        assertEquals("25.25", Note.formatAmount(25.25));
    }
}
