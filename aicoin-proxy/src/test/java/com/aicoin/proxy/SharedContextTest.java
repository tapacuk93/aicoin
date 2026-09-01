package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The record every panelist is given on every turn. Two properties matter and pull against each
 * other: a turn must always be able to see the request and the answer under discussion, and the
 * record must not grow without limit, because it is sent to every panelist on every round and every
 * character of it is billed as input.
 */
class SharedContextTest {

    private static final int ROOMY = 100_000;

    private static SharedContext withOneRound(int maxChars) {
        SharedContext context = new SharedContext("What is 2+2?", "The caller is a calculator.", maxChars);
        context.addDrafts(List.of("anthropic", "kimi"), List.of("four", "4"));
        context.addAnswer("The answer as it now stands", "four");
        context.addReviews(1, List.of("openai"), List.of("say it in digits"), List.of("google"));
        return context;
    }

    @Test
    void carriesTheRequestTheBackgroundAndEveryContributionInOrder() {
        String rendered = withOneRound(ROOMY).render();

        assertTrue(rendered.contains("What is 2+2?"));
        assertTrue(rendered.contains("The caller is a calculator."), "the caller's background is shared too");
        assertTrue(rendered.contains("four") && rendered.contains("say it in digits"));
        assertTrue(rendered.indexOf("What is 2+2?") < rendered.indexOf("say it in digits"),
                "the record reads in the order things happened");
    }

    @Test
    void contributionsAreAttributedToTheModelThatMadeThem() {
        // Unattributed, a reviewer cannot tell one model's objection from three models agreeing,
        // and the editor cannot weigh a disagreement it can't see the sides of.
        String rendered = withOneRound(ROOMY).render();
        assertTrue(rendered.contains("Claude") && rendered.contains("Kimi"));
        assertTrue(rendered.contains("GPT"), "the reviewer who commented is named");
        assertTrue(rendered.contains("Gemini"), "so is the reviewer who had nothing to say");
    }

    @Test
    void aNewAnswerSupersedesTheOldOneWithoutLosingTheRequest() {
        SharedContext context = withOneRound(ROOMY);
        context.addAnswer("The answer as it now stands (revised after round 1)", "4");
        String rendered = context.render();
        assertTrue(rendered.contains("revised after round 1"));
        assertTrue(rendered.contains("What is 2+2?"));
    }

    @Test
    void pastTheLimitTheOldestRoundsGoAndSayThatTheyWent() {
        // The record grows by a round per round and is re-sent to every panelist each time, so
        // something has to give; the oldest rounds are what the panel has already moved past.
        SharedContext context = new SharedContext("REQUEST-MARKER", null, 900);
        context.addDrafts(List.of("anthropic"), List.of("d".repeat(400)));
        context.addAnswer("The answer as it now stands", "first answer");
        context.addReviews(1, List.of("openai"), List.of("r".repeat(400)), List.of());
        context.addAnswer("The answer as it now stands (revised after round 1)", "CURRENT-ANSWER-MARKER");
        context.addReviews(2, List.of("openai"), List.of("the newest comment"), List.of());

        String rendered = context.render();

        assertTrue(rendered.contains("REQUEST-MARKER"), "the request is never dropped");
        assertTrue(rendered.contains("CURRENT-ANSWER-MARKER"), "the answer under discussion is never dropped");
        assertTrue(rendered.contains("the newest comment"), "the latest round survives");
        assertFalse(rendered.contains("d".repeat(400)), "the oldest round went");
        assertTrue(rendered.contains("omitted"), "and the turn is told that something went");
        assertTrue(rendered.length() <= 900 + 64, "the point of the limit is the size: " + rendered.length());
    }

    @Test
    void aRecordThatIsAllRequestIsTruncatedRatherThanFailingUpstream() {
        // Nothing here is droppable, so the only alternative to trimming is a context-length error
        // from the provider, which costs the call for no benefit.
        SharedContext context = new SharedContext("x".repeat(5000), null, 500);
        String rendered = context.render();
        assertTrue(rendered.length() <= 500 + 32);
        assertTrue(rendered.contains("truncated"));
    }

    @Test
    void everyTurnGetsTheRecordPlusItsOwnInstruction() {
        String turn = withOneRound(ROOMY).forTurn("Review the answer.");
        assertTrue(turn.contains("What is 2+2?"));
        assertTrue(turn.trim().endsWith("Review the answer."), "the task line comes last, after the record");
    }
}
