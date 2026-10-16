package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A passage cut into pieces short enough for a speech model to read reliably.
 *
 * <p>Gemini's speech models are language models: given a long passage they can skip a sentence,
 * repeat one, or drift into something that isn't in the text — the longer the passage, the likelier.
 * Pieces of a paragraph or so are read faithfully, and joined back together they are the passage.
 * Cut at sentence ends wherever possible (a cut mid-sentence is heard as a pause in the wrong
 * place), at commas and the like when one sentence is itself too long, and at a space only when
 * nothing better exists.
 */
final class SpeechChunks {

    /**
     * How long a piece may be. About a minute of speech: comfortably inside what the model reads
     * faithfully, and long enough that a card is rarely more than two or three pieces.
     */
    static final int MAX_CHARS = 1000;

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
