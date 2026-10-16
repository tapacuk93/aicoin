package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A passage cut into pieces, so a long one is synthesised in parallel instead of in one long wait.
 *
 * <p>Not for accuracy. Measured against gemini-3.8-flash-tts (Oct 2026) — passages of 500 to 3,000
 * characters in English, Basque and Igbo, each recording transcribed back and compared word by
 * word — every length was read through to the end with nothing skipped or repeated, and the
 * agreement did not fall with length. What does grow with length is the wait: about 2.5 s plus
 * 17 ms a character (113 chars 4 s, 424 chars 8.5 s, 975 chars 17 s, 1,893 chars 31 s, 2,982
 * chars 37.5 s). Pieces asked for at once arrive in the time of the longest piece.
 *
 * <p>Cut at sentence ends wherever possible (a cut mid-sentence is heard as a pause in the wrong
 * place), at commas and the like when one sentence is itself too long, and at a space only when
 * nothing better exists.
 */
final class SpeechChunks {

    /**
     * How long a piece may be: what comes back in under fifteen seconds on the curve above — the
     * patience the app gives the next card's narration before asking another service as well — so
     * a long card is never passed over merely for being long. Shorter would be quicker still, but
     * every cut is a seam where the voice starts a fresh sentence, and every piece is a call.
     */
    static final int MAX_CHARS = 800;

    /** A sentence end: Latin, CJK, Devanagari, Ethiopic and Arabic full stops, and the ellipsis. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?…。！？।॥።؟])\\s+|(?<=[。！？])");
    private static final Pattern CLAUSE_END = Pattern.compile("(?<=[,;:，、；：،])\\s*");

    private SpeechChunks() {
    }

    static List<String> split(String text) {
        return split(text, MAX_CHARS);
    }

    static List<String> split(String text, int max) {
        String trimmed = text == null ? "" : text.trim();
        List<String> chunks = new ArrayList<>();
        if (trimmed.isEmpty()) {
            return chunks;
        }
        if (trimmed.length() <= max) {
            chunks.add(trimmed);
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : pieces(trimmed, SENTENCE_END)) {
            for (String part : sentence.length() <= max ? List.of(sentence) : fit(sentence, max)) {
                if (current.length() > 0 && current.length() + 1 + part.length() > max) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(part);
            }
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /** One over-long sentence, cut at clause marks, then at spaces, then wherever it must be. */
    private static List<String> fit(String sentence, int max) {
        List<String> out = new ArrayList<>();
        for (String clause : pieces(sentence, CLAUSE_END)) {
            String rest = clause;
            while (rest.length() > max) {
                int cut = rest.lastIndexOf(' ', max);
                if (cut <= 0) {
                    cut = max;
                }
                out.add(rest.substring(0, cut).trim());
                rest = rest.substring(cut).trim();
            }
            if (!rest.isEmpty()) {
                out.add(rest);
            }
        }
        return out;
    }

    private static List<String> pieces(String text, Pattern boundary) {
        List<String> out = new ArrayList<>();
        Matcher matcher = boundary.matcher(text);
        int start = 0;
        while (matcher.find()) {
            if (matcher.end() == 0 || matcher.end() <= start) {
                continue;
            }
            String piece = text.substring(start, matcher.start()).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = matcher.end();
        }
        String last = text.substring(start).trim();
        if (!last.isEmpty()) {
            out.add(last);
        }
        return out;
    }
}
