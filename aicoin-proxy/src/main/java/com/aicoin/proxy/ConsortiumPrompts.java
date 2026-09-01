package com.aicoin.proxy;

import java.util.List;
import java.util.Locale;

/**
 * The words a consortium call puts in front of each panelist, and the one signal it reads back out
 * of a review, per CONTRACT.md's "Consortium" section. Pure text — no I/O — so the round logic can
 * be pinned by tests without a provider.
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

    static String draftSystem() {
        return "You are one member of a panel of AI models. Every member is answering the same request"
                + " independently, and the answers will afterwards be merged by an editor and reviewed by"
                + " the whole panel.\n\n"
                + "Answer the request as well as you can. Be concrete and correct; say plainly when"
                + " something is uncertain rather than guessing fluently. Do not mention the panel, the"
                + " review process, or that you are one of several models. Output only the answer.";
    }

    static String mergeSystem(int draftCount) {
        return "You are the editor of a panel of " + draftCount + " AI models that have each answered the"
                + " same request independently. Their answers follow.\n\n"
                + "Produce the single best answer to the original request: take what each draft gets right,"
                + " drop what is wrong or unsupported, and resolve contradictions between them on the"
                + " merits rather than by majority. Where the drafts genuinely disagree and you cannot"
                + " settle it, say so in the answer instead of picking silently.\n\n"
                + "Do not describe the drafts, credit them, or mention the panel. Output only the answer.";
    }

    static String reviewSystem() {
        return "You are reviewing a candidate answer written for the request below by a panel you are part"
                + " of. Your job is to catch what is wrong with it, not to polish it.\n\n"
                + "Report only substantive problems: factual errors, claims that are not supported, parts"
                + " of the request left unanswered, reasoning that does not follow, and anything actively"
                + " misleading. Ignore matters of style, tone, formatting and personal preference; do not"
                + " suggest additions that the request did not ask for.\n\n"
                + "If you find such problems, list them, each on its own line, most serious first. If the"
                + " answer is correct and complete, reply with exactly " + CLEAN_REVIEW + " and nothing"
                + " else — no preamble, no praise, no caveats.";
    }

    static String reviseSystem(int commentCount) {
        return "You are the editor of a panel of AI models. Below are the original request, the current"
                + " answer, and " + commentCount + " independent reviews of it.\n\n"
                + "Revise the answer to fix every comment that is right. Reject the ones that are wrong or"
                + " that ask for something the request did not — a reviewer can be mistaken, and giving"
                + " way to a mistaken one makes the answer worse. Change nothing the comments did not"
                + " reach.\n\n"
                + "Do not mention the reviews, the comments or the panel, and do not describe your"
                + " changes. Output only the revised answer.";
    }

    /** The drafts, labelled by panelist, as one user message for the merge turn. */
    static String mergeUser(String request, List<String> providers, List<String> drafts) {
        StringBuilder out = new StringBuilder("The request was:\n\n").append(request).append("\n\n");
        for (int i = 0; i < drafts.size(); i++) {
            out.append("--- Draft from ").append(ChatAdapter.displayName(providers.get(i))).append(" ---\n")
                    .append(drafts.get(i)).append("\n\n");
        }
        return out.toString();
    }

    static String reviewUser(String request, String answer) {
        return "The request was:\n\n" + request + "\n\n--- Candidate answer ---\n" + answer;
    }

    /** The request, the answer under review, and every comment raised against it, for the revise turn. */
    static String reviseUser(String request, String answer, List<String> providers, List<String> comments) {
        StringBuilder out = new StringBuilder("The request was:\n\n").append(request)
                .append("\n\n--- Current answer ---\n").append(answer).append("\n");
        for (int i = 0; i < comments.size(); i++) {
            out.append("\n--- Review from ").append(ChatAdapter.displayName(providers.get(i))).append(" ---\n")
                    .append(comments.get(i)).append("\n");
        }
        return out.toString();
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
