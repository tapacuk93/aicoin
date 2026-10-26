package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code library} a consortium call may carry, per CONTRACT.md's "Consortium" section: a
 * book's contents in reading order — dotted refs giving the tree ({@code 1}, {@code 1.1},
 * {@code 1.1.2}) — with the text of every part already written, and the one being written now.
 *
 * <p>The point is that a panel writing chapter 3.2 should be able to see the whole book and read
 * what it needs of it, without being handed all of it. Pasting every earlier chapter into
 * {@code context} would put hundreds of thousands of characters in front of every panelist on
 * every turn — billed as input each time — when a turn needs, at most, the two or three chapters
 * the new one leans on. So the record carries only the <em>index</em> (ref, title, length or
 * "not written yet", the current one marked), and a panelist that needs a chapter asks for it by
 * ref ({@code READ 2.3}); the proxy then adds that chapter's text to the shared record, once, for
 * everybody. Which chapters matter is a judgement for the model that has read the request, not
 * for a rule written here.
 *
 * <p>Pure data — no I/O — so the parsing, the limits and the index text are pinned by tests.
 */
final class ConsortiumLibrary {

    /** One part of the book: a ref, a title, and its text if it has been written. */
    static final class Entry {
        private final String ref;
        private final String title;
        private final String text;

        Entry(String ref, String title, String text) {
            this.ref = ref;
            this.title = title;
            this.text = text;
        }

        String ref() {
            return ref;
        }

        String title() {
            return title;
        }

        /** The written text, or null for a part that is planned but not written yet. */
        String text() {
            return text;
        }

        boolean isWritten() {
            return text != null && !text.trim().isEmpty();
        }

        /** Depth in the tree: {@code 1} is 0, {@code 1.1} is 1. */
        int depth() {
            int depth = 0;
            for (int i = 0; i < ref.length(); i++) {
                if (ref.charAt(i) == '.') {
                    depth++;
                }
            }
            return depth;
        }
    }

    /** Why a {@code library} was refused; the message goes back to the caller as a 400. */
    static final class Invalid extends Exception {
        Invalid(String message) {
            super(message);
        }
    }

    private final String current;
    private final List<Entry> entries;
    private final Map<String, Entry> byRef;

    private ConsortiumLibrary(String current, List<Entry> entries) {
        this.current = current;
        this.entries = Collections.unmodifiableList(entries);
        this.byRef = new LinkedHashMap<>();
        for (Entry entry : entries) {
            byRef.put(entry.ref, entry);
        }
    }

    /**
     * Reads a request's {@code library} field.
     *
     * @return null when the field is absent (or JSON null); otherwise the library
     * @throws Invalid when it is present but malformed or over the configured limits
     */
    static ConsortiumLibrary parse(Object raw, int maxChars, int maxEntries) throws Invalid {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map)) {
            throw new Invalid("library must be an object with current and entries");
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        Object entriesObj = map.get("entries");
        if (!(entriesObj instanceof List)) {
            throw new Invalid("library.entries must be a list");
        }
        List<?> rawEntries = (List<?>) entriesObj;
        if (rawEntries.size() > maxEntries) {
            throw new Invalid("library may list at most " + maxEntries + " entries");
        }
        List<Entry> entries = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        long totalChars = 0;
        for (Object item : rawEntries) {
            if (!(item instanceof Map)) {
                throw new Invalid("each library entry must be an object with ref and title");
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object refObj = entry.get("ref");
            // A ref written as a bare number in JSON ("ref": 1) is still the ref "1"; anything
            // else — a list, an object, nothing — cannot name a chapter.
            String ref = refObj instanceof String ? ((String) refObj).trim()
                    : refObj instanceof Number ? refObj.toString() : null;
            if (ref == null || ref.isEmpty() || ref.chars().anyMatch(Character::isWhitespace)) {
                throw new Invalid("each library entry needs a ref without spaces");
            }
            if (seen.put(ref, Boolean.TRUE) != null) {
                throw new Invalid("library ref " + ref + " appears twice");
            }
            Object titleObj = entry.get("title");
            String title = titleObj instanceof String ? ((String) titleObj).trim() : "";
            Object textObj = entry.get("text");
            if (textObj != null && !(textObj instanceof String)) {
                throw new Invalid("library entry " + ref + ": text must be a string");
            }
            String text = (String) textObj;
            if (text != null) {
                totalChars += text.length();
                if (totalChars > maxChars) {
                    throw new Invalid("library text must be at most " + maxChars + " characters in total");
                }
            }
            entries.add(new Entry(ref, title, text));
        }
        Object currentObj = map.get("current");
        String current = currentObj instanceof String ? ((String) currentObj).trim()
                : currentObj instanceof Number ? currentObj.toString() : null;
        if (current != null && current.isEmpty()) {
            current = null;
        }
        if (current != null && !seen.containsKey(current)) {
            throw new Invalid("library.current " + current + " is not one of its entries");
        }
        return new ConsortiumLibrary(current, entries);
    }

    String current() {
        return current;
    }

    List<Entry> entries() {
        return entries;
    }

    /** The entry with this ref, or null. */
    Entry get(String ref) {
        return byRef.get(ref);
    }

    /**
     * Words in a chapter's text, counted after its markup is taken out — the index states
     * lengths so a panelist can judge what a read will cost before asking for it, and a count
     * of HTML tags would mislead it.
     */
    static int wordCount(String text) {
        if (text == null) {
            return 0;
        }
        String plain = text.replaceAll("(?s)<!--.*?-->", " ").replaceAll("<[^>]*>", " ")
                .replaceAll("&[a-zA-Z#0-9]+;", " ");
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < plain.length(); i++) {
            boolean letter = !Character.isWhitespace(plain.charAt(i));
            if (letter && !inWord) {
                words++;
            }
            inWord = letter;
        }
        return words;
    }

    /**
     * The contents as the panel sees them: one line per entry, indented by depth, with its length
     * or "not written yet", and the part being written now marked. This is all of the library that
     * goes into the record unasked — the text itself comes in only by {@code READ}.
     */
    String renderIndex() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            out.append("  ".repeat(entry.depth())).append(entry.ref);
            if (!entry.title.isEmpty()) {
                out.append("  ").append(entry.title);
            }
            if (entry.ref.equals(current)) {
                out.append("  <== being written now (this request)");
            } else if (entry.isWritten()) {
                out.append("  (").append(wordCount(entry.text)).append(" words)");
            } else {
                out.append("  (not written yet)");
            }
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }
}
