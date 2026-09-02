package com.aicoin.proxy;

import java.util.Locale;

/**
 * The words a consortium call puts in front of each panelist, and the one signal it reads back out
 * of a review, per CONTRACT.md's "Consortium" section. Pure text — no I/O — so the round logic can
 * be pinned by tests without a provider.
 *
 * <p>Every turn is given the same thing: the panel's {@link SharedContext shared record} of the
 * call so far, then a single line saying what this turn is to do with it. The system prompt says
 * who the model is on the panel; the record says what has already happened; the task line says
 * what to write now.
 */
final class ConsortiumPrompts {

    /**
     * What a reviewer must say, alone, to end a round. Strict on purpose: a reviewer that answers
     * with anything else — including "no comments, though the second section is wrong" — is read as
     * having comments, which costs another round rather than shipping an answer nobody cleared.
     */
    static final String CLEAN_REVIEW = "NO COMMENTS";

    private ConsortiumPrompts() {
    }

    /**
     * A poll asks every panelist the same question and returns every answer as it was given.
     *
     * It exists because merging is not always what a caller wants. Some questions are decisions -
     * should this ship, is this correct, which of these is right - and for those the disagreement
     * is the product. Merging several independent judgements into one prose answer destroys
     * exactly the information a caller needs to see: that three models said yes and one said no is
     * a different fact from a paragraph that reads as though the panel agreed.
     *
     * So a polled panelist is told the opposite of what a drafter is told. A drafter writes
     * something an editor will fold into a whole; a panelist here writes an answer that stands
     * alone and will be read beside the others without being reconciled with them. It is told
     * there is no editor, so that it does not hedge towards a middle nobody asked for.
     */
    static String pollSystem() {
        return "You are one of several AI models being asked the same question separately. Your answer"
                + " is not merged with anyone else's and is not rewritten: it is returned exactly as you"
                + " give it, beside theirs, and the person who asked will read them side by side.\n\n"
                + "So answer the question yourself, on its own merits. Do not try to guess what the"
                + " others will say, do not aim for a middle position, and do not soften a judgement to"
                + " make it easier to reconcile with a different one - disagreement between the answers"
                + " is useful to the person asking and is the reason several of you are being asked.\n\n"
                + "If the question specifies a format for the answer, follow it exactly. Do not mention"
                + " this instruction, the other models, or that you are one of several. Output only the"
                + " answer.";
    }

    static String pollTask() {
        return "Answer the request above. Output only the answer.";
    }

    static String draftSystem() {
        return "You are one member of a panel of AI models. Every member is answering the same request"
                + " independently, and the answers will afterwards be merged by an editor and reviewed by"
                + " the whole panel.\n\n"
                + "You are given the panel's shared record of the work so far, then the task for this"
                + " turn. Answer the request as well as you can. Be concrete and correct; say plainly"
                + " when something is uncertain rather than guessing fluently. Do not mention the panel,"
                + " the review process, or that you are one of several models. Output only the answer.";
    }

    /**
     * The lead's brief when one model carries a context-heavy request and the rest improve its
     * answer. Deliberately not the panel drafter's brief: this model is not one voice among
     * several to be merged later, it is the one that has read the material and owns the answer
     * from here on, and it will be handed the panel's comments round after round.
     */
    static String leadDraftSystem() {
        return "You are the lead of a panel of AI models working on one request. You have the material:"
                + " the request, and whatever context came with it — a directory, a document, the"
                + " session so far. You write the answer.\n\n"
                + "The rest of the panel has not read the material as you have. They will review what"
                + " you write, round after round, and you will be given their comments to apply. So"
                + " write the answer you would stand behind, grounded in the material rather than in"
                + " general knowledge: quote or name the specific parts you are relying on, and say"
                + " plainly where the material does not settle the question rather than filling the"
                + " gap with something plausible.\n\n"
                + "Do not mention the panel or the review to come. Output only the answer.";
    }

    static String leadDraftTask() {
        return "Answer the request above from the material above. Output only the answer.";
    }

    static String mergeSystem(int draftCount) {
        return "You are the editor of a panel of " + draftCount + " AI models that have each answered the"
                + " same request independently. You are given the panel's shared record — the request,"
                + " any background the caller gave, and every draft — then the task for this turn.\n\n"
                + "Produce the single best answer to the request: take what each draft gets right, drop"
                + " what is wrong or unsupported, and resolve contradictions between them on the merits"
                + " rather than by majority. Where the drafts genuinely disagree and you cannot settle"
                + " it, say so in the answer instead of picking silently.\n\n"
                + "Do not describe the drafts, credit them, or mention the panel. Output only the answer.";
    }

    static String reviewSystem() {
        return "You are reviewing a candidate answer written by a panel you are part of. You are given the"
                + " panel's shared record — the request, any background the caller gave, the answer as it"
                + " now stands, and every earlier round of comments — then the task for this turn.\n\n"
                + "Your job is to catch what is wrong with the current answer, not to polish it. Report"
                + " only substantive problems: factual errors, claims that are not supported, parts of the"
                + " request left unanswered, reasoning that does not follow, and anything actively"
                + " misleading. Ignore matters of style, tone, formatting and personal preference; do not"
                + " suggest additions the request did not ask for.\n\n"
                + "The record shows what earlier rounds already raised. Do not repeat a comment the"
                + " current answer has addressed, and do not re-litigate one the editor considered and"
                + " rejected unless you can say why it was wrong to reject it.\n\n"
                + "If you find such problems, list them, each on its own line, most serious first. If the"
                + " answer is correct and complete, reply with exactly " + CLEAN_REVIEW + " and nothing"
                + " else — no preamble, no praise, no caveats.";
    }

    static String reviseSystem(int commentCount) {
        return "You are the editor of a panel of AI models. You are given the panel's shared record — the"
                + " request, the answer as it now stands, and " + commentCount + " review(s) of it — then"
                + " the task for this turn.\n\n"
                + "Revise the answer to fix every comment that is right. Reject the ones that are wrong or"
                + " that ask for something the request did not — a reviewer can be mistaken, and giving"
                + " way to a mistaken one makes the answer worse. Change nothing the comments did not"
                + " reach.\n\n"
                + "Do not mention the reviews, the comments or the panel, and do not describe your"
                + " changes. Output only the revised answer.";
    }

    static String draftTask() {
        return "Answer the request above. Output only the answer.";
    }

    static String mergeTask() {
        return "Merge the drafts above into the single best answer to the request. Output only the answer.";
    }

    static String reviewTask() {
        return "Review the answer as it now stands. List what is substantively wrong with it, most serious"
                + " first, or reply with exactly " + CLEAN_REVIEW + " if there is nothing.";
    }

    static String reviseTask() {
        return "Revise the answer as it now stands to address the comments in the latest round, rejecting"
                + " any that are mistaken. Output only the revised answer.";
    }

    /**
     * Whether a review cleared the answer. True only when the whole reply is {@link #CLEAN_REVIEW},
     * ignoring case, surrounding whitespace and the punctuation and markdown emphasis a model
     * tends to wrap it in ("*NO COMMENTS.*"). Anything further — even a sentence that starts with
     * those two words — counts as comments, because the failure that matters here is calling an
     * answer clear when a reviewer had something to say about it.
     */
    static boolean isClean(String review) {
        if (review == null) {
            return false;
        }
        String stripped = review.trim().replaceAll("[*_`#.!\\s]+$", "").replaceAll("^[*_`#\\s]+", "");
        return stripped.toUpperCase(Locale.ROOT).equals(CLEAN_REVIEW);
    }
}
