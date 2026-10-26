package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code library} a consortium call may carry: what is accepted, what is refused before any
 * coin is spent, and the index — the only part of it that goes into the record unasked.
 */
class ConsortiumLibraryTest {

    private static Object json(String text) {
        return Json.load(text);
    }

    private static ConsortiumLibrary parse(String text) throws ConsortiumLibrary.Invalid {
        return ConsortiumLibrary.parse(json(text), 600_000, 2000);
    }

    private static final String BOOK = "{\"current\":\"2.1\",\"entries\":["
            + "{\"ref\":\"1\",\"title\":\"Origins\",\"text\":\"<p>One two <b>three</b></p><math><mi>x</mi></math>\"},"
            + "{\"ref\":\"1.1\",\"title\":\"Rivers\",\"text\":\"<p>Water runs downhill.</p>\"},"
            + "{\"ref\":\"2\",\"title\":\"Trade\"},"
            + "{\"ref\":\"2.1\",\"title\":\"Routes\"},"
            + "{\"ref\":\"2.2\",\"title\":\"Ports\"}]}";

    @Test
    void noLibraryIsNotAnError() throws Exception {
        assertNull(ConsortiumLibrary.parse(null, 10, 10));
    }

    @Test
    void readsEntriesInOrderAndKnowsWhichAreWritten() throws Exception {
        ConsortiumLibrary library = parse(BOOK);
        assertEquals("2.1", library.current());
        assertEquals(5, library.entries().size());
        assertTrue(library.get("1.1").isWritten());
        assertFalse(library.get("2.2").isWritten(), "an entry without text is planned, not written");
        assertNull(library.get("9"));
    }

    @Test
    void theIndexIsATreeWithLengthsAndTheCurrentPartMarkedButNoText() throws Exception {
        String index = parse(BOOK).renderIndex();

        assertTrue(index.contains("1  Origins  (4 words)"), "markup is not counted as words: " + index);
        assertTrue(index.contains("\n  1.1  Rivers  (3 words)"), "a child is indented under its parent: " + index);
        assertTrue(index.contains("2.2  Ports  (not written yet)"));
        assertTrue(index.contains("2.1  Routes  <== being written now"));
        assertFalse(index.contains("downhill"), "the text itself only comes in by READ");
    }

    @Test
    void malformedLibrariesAreRefusedBeforeAnythingIsSpent() {
        assertThrows(ConsortiumLibrary.Invalid.class, () -> parse("[1,2]"));
        assertThrows(ConsortiumLibrary.Invalid.class, () -> parse("{\"entries\":\"no\"}"));
        assertThrows(ConsortiumLibrary.Invalid.class, () -> parse("{\"entries\":[{\"title\":\"no ref\"}]}"));
        assertThrows(ConsortiumLibrary.Invalid.class,
                () -> parse("{\"entries\":[{\"ref\":\"1\"},{\"ref\":\"1\"}]}"), "a ref names one part");
        assertThrows(ConsortiumLibrary.Invalid.class,
                () -> parse("{\"current\":\"3\",\"entries\":[{\"ref\":\"1\"}]}"), "current must be in the contents");
        assertThrows(ConsortiumLibrary.Invalid.class,
                () -> parse("{\"entries\":[{\"ref\":\"1\",\"text\":5}]}"));
    }

    @Test
    void theConfiguredLimitsAreEnforced() {
        Object big = json("{\"entries\":[{\"ref\":\"1\",\"text\":\"12345\"},{\"ref\":\"2\",\"text\":\"123456\"}]}");
        assertThrows(ConsortiumLibrary.Invalid.class, () -> ConsortiumLibrary.parse(big, 10, 100),
                "the limit is on the total, not per entry");
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            many.add(Map.of("ref", String.valueOf(i)));
        }
        assertThrows(ConsortiumLibrary.Invalid.class,
                () -> ConsortiumLibrary.parse(Map.of("entries", many), 100, 3));
    }

    @Test
    void wordCountIgnoresTagsAndEntities() {
        assertEquals(3, ConsortiumLibrary.wordCount("<h1>A&nbsp;title</h1>\n<p>here</p><!-- a comment -->"));
        assertEquals(0, ConsortiumLibrary.wordCount(null));
    }
}
