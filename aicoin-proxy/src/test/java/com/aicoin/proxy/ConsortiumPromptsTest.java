package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The signal that ends a consortium call. Everything else about a review is free text; this one
 * reading decides whether the rounds stop, so it is deliberately strict in the direction that
 * costs a round rather than the one that ships an answer a reviewer had objections to.
 */
class ConsortiumPromptsTest {

    @Test
    void theExactPhraseClearsTheRound() {
        assertTrue(ConsortiumPrompts.isClean("NO COMMENTS"));
        assertTrue(ConsortiumPrompts.isClean("  no comments  "));
        assertTrue(ConsortiumPrompts.isClean("No comments."));
        // Models reliably dress a bare phrase in emphasis when the rest of their answer is prose.
        assertTrue(ConsortiumPrompts.isClean("**NO COMMENTS**"));
        assertTrue(ConsortiumPrompts.isClean("`NO COMMENTS`"));
    }

    @Test
    void aPhraseWithAnythingAfterItIsStillComments() {
        // The failure that matters: reading "no comments, but X is wrong" as agreement would
        // publish an answer that a reviewer had just objected to.
        assertFalse(ConsortiumPrompts.isClean("No comments, though the second section is wrong."));
        assertFalse(ConsortiumPrompts.isClean("NO COMMENTS on the structure. The date is wrong."));
        assertFalse(ConsortiumPrompts.isClean("I have no comments about the tone, but the maths is off."));
    }

    @Test
    void silenceIsNotAgreement() {
        // A panelist that failed contributes no review at all; an empty reply is not a clearance.
        assertFalse(ConsortiumPrompts.isClean(null));
        assertFalse(ConsortiumPrompts.isClean(""));
        assertFalse(ConsortiumPrompts.isClean("   "));
    }

    @Test
    void reviewersAreToldTheExactPhraseTheyMustUse() {
        // The prompt and the parser have to agree, or every round reads as having comments and
        // every call runs to the round cap.
        assertTrue(ConsortiumPrompts.reviewSystem().contains(ConsortiumPrompts.CLEAN_REVIEW));
        assertTrue(ConsortiumPrompts.isClean(ConsortiumPrompts.CLEAN_REVIEW));
    }

    @Test
    void mergeAndReviseTurnsCarryTheRequestAndEveryContribution() {
        String merged = ConsortiumPrompts.mergeUser("What is 2+2?", List.of("anthropic", "kimi"),
                List.of("four", "4"));
        assertTrue(merged.contains("What is 2+2?"));
        assertTrue(merged.contains("four") && merged.contains("4"));
        assertTrue(merged.contains("Claude") && merged.contains("Kimi"),
                "drafts are labelled so the editor can weigh disagreement between named panelists");

        String revise = ConsortiumPrompts.reviseUser("What is 2+2?", "five", List.of("openai"),
                List.of("five is wrong; it is four"));
        assertTrue(revise.contains("five is wrong; it is four"));
        assertTrue(revise.contains("What is 2+2?"), "the editor needs the request, not only the comments");
    }
}
