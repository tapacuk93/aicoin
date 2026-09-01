package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void everyTurnIsToldWhatToDoWithTheSharedRecord() {
        // The system prompt says who the model is; the record says what has happened; the task
        // line says what to write now. A turn missing the last one is a model reading a transcript
        // with no instruction.
        assertTrue(ConsortiumPrompts.draftTask().toLowerCase().contains("answer"));
        assertTrue(ConsortiumPrompts.mergeTask().toLowerCase().contains("merge"));
        assertTrue(ConsortiumPrompts.reviewTask().contains(ConsortiumPrompts.CLEAN_REVIEW));
        assertTrue(ConsortiumPrompts.reviseTask().toLowerCase().contains("revise"));
    }

    @Test
    void reviewersAreToldNotToRepeatWhatTheRecordAlreadySettled() {
        // The point of giving reviewers the earlier rounds: without this they raise the same
        // objection every round, and a call that could have settled runs to the cap instead.
        String system = ConsortiumPrompts.reviewSystem().toLowerCase();
        assertTrue(system.contains("earlier round"));
        assertTrue(system.contains("do not repeat"));
    }
}
