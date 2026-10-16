package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cutting a long passage into pieces a speech model reads faithfully — and joining them back. */
class SpeechChunksTest {

    @Test
    void piecesAreAtMostEightHundredCharacters() {
        assertEquals(800, SpeechChunks.MAX_CHARS);
        String passage = "A sentence of moderate length that keeps on going for a while. ".repeat(60).trim();
        assertTrue(SpeechChunks.split(passage).stream().allMatch(p -> p.length() <= 800));
    }

    @Test
    void aShortPassageIsOnePiece() {
        assertEquals(List.of("Kaixo, zer moduz?"), SpeechChunks.split("  Kaixo, zer moduz?  "));
        assertTrue(SpeechChunks.split("   ").isEmpty());
    }

    @Test
    void aLongPassageIsCutAtSentenceEndsAndLosesNothing() {
        String sentence = "This sentence is exactly as long as it needs to be for the test. ";
        String passage = sentence.repeat(40).trim();
        List<String> pieces = SpeechChunks.split(passage, 300);
        assertTrue(pieces.size() > 1);
        for (String piece : pieces) {
            assertTrue(piece.length() <= 300, piece.length() + " chars");
            assertTrue(piece.endsWith("."), "cut at a sentence end: " + piece);
        }
        assertEquals(passage, String.join(" ", pieces), "every word, in order");
    }

    @Test
    void sentenceEndsInOtherScriptsCount() {
        List<String> japanese = SpeechChunks.split("今日は晴れです。".repeat(20), 40);
        assertTrue(japanese.size() > 1);
        assertTrue(japanese.stream().allMatch(p -> p.endsWith("。") && p.length() <= 40));
        List<String> amharic = SpeechChunks.split("ሰላም ነው። ".repeat(20).trim(), 30);
        assertTrue(amharic.stream().allMatch(p -> p.endsWith("።") && p.length() <= 30));
    }

    @Test
    void anOverlongSentenceIsCutAtClausesThenSpaces() {
        String clause = "and then another clause follows, ";
        List<String> pieces = SpeechChunks.split(clause.repeat(30).trim(), 100);
        assertTrue(pieces.stream().allMatch(p -> p.length() <= 100));
        String words = "word ".repeat(100).trim();
        List<String> cut = SpeechChunks.split(words, 50);
        assertTrue(cut.stream().allMatch(p -> p.length() <= 50 && !p.startsWith(" ") && !p.endsWith(" ")));
        assertEquals(words, String.join(" ", cut));
    }

    @Test
    void piecesJoinOnlyWhenTheyAllArrivedInTheSameFormat() {
        MediaAdapter.Pcm a = new MediaAdapter.Pcm(new byte[] {1, 2}, 24000, 1);
        MediaAdapter.Pcm b = new MediaAdapter.Pcm(new byte[] {3, 4}, 24000, 1);
        MediaAdapter.Pcm joined = MediaAdapter.joined(List.of(a, b));
        assertNotNull(joined);
        assertEquals(4, joined.samples.length);
        assertEquals(3, joined.samples[2]);
        assertNull(MediaAdapter.joined(java.util.Arrays.asList(a, null)), "a missing piece is a missing passage");
        assertNull(MediaAdapter.joined(List.of(a, new MediaAdapter.Pcm(new byte[] {5, 6}, 16000, 1))));
    }
}
