package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What a request gets tagged as. The tagger only orders a provider list, so being wrong is cheap —
 * but being unstable is not, since a request that tags differently each time routes differently
 * each time and a failure stops being reproducible.
 */
class SubjectTaggerTest {

    @Test
    void tagsTheObviousCases() {
        assertEquals("code", SubjectTagger.tag("Why does this Java function throw a null pointer?", null));
        assertEquals("math", SubjectTagger.tag("Calculate the derivative of x^3 + 2x", null));
        assertEquals("translation", SubjectTagger.tag("Translate this paragraph into Japanese", null));
        assertEquals("legal", SubjectTagger.tag("Is this indemnity clause enforceable?", null));
        assertEquals("creative", SubjectTagger.tag("Write a short story about a lighthouse", null));
    }

    @Test
    void anythingElseIsGeneral() {
        assertEquals(SubjectTagger.GENERAL, SubjectTagger.tag("What time is it in Kyiv?", null));
        assertEquals(SubjectTagger.GENERAL, SubjectTagger.tag("", null));
        assertEquals(SubjectTagger.GENERAL, SubjectTagger.tag(null, null));
    }

    @Test
    void theRequestOutweighsTheContext() {
        // A legal question asked about a codebase is a legal question. Context colours the tag; it
        // does not decide it, or every question asked with a directory attached would tag as code.
        String context = "package com.example; public class Widget { void compile() {} } // java code";
        assertEquals("legal", SubjectTagger.tag("Does this license permit redistribution?", context));
    }

    @Test
    void contextBreaksATieTheRequestCannot() {
        String tagged = SubjectTagger.tag("Have a look at this and tell me what you think",
                "Stack trace from the unit test: java.lang.NullPointerException in Widget.compile");
        assertEquals("code", tagged);
    }

    @Test
    void onlyTheHeadOfALargeContextIsRead() {
        // A megabyte of pasted material must not be re-scanned on every call, and must not drown
        // out the question either.
        String padding = "x".repeat(50_000);
        assertEquals("math", SubjectTagger.tag("Solve for x in this equation", padding + " translate this"));
    }

    @Test
    void theSameRequestAlwaysTagsTheSameWay() {
        String prompt = "Write documentation for this API and translate it into German";
        String first = SubjectTagger.tag(prompt, null);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, SubjectTagger.tag(prompt, null));
        }
    }

    @Test
    void everyTagItCanReturnIsInTheDeclaredList() {
        for (String subject : SubjectTagger.subjects()) {
            assertTrue(SubjectTagger.isKnown(subject), subject);
        }
        assertTrue(SubjectTagger.isKnown("CODE"), "known subjects are case-insensitive");
        assertTrue(!SubjectTagger.isKnown("astrology"));
        assertTrue(!SubjectTagger.isKnown(null));
    }
}
