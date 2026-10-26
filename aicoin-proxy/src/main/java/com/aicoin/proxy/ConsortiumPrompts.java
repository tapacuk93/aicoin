package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * What a single model says instead of an answer when it judges the request too hard to answer
     * alone — the signal {@code POST /text} reads to escalate the same request to the whole panel.
     *
     * <p>Strict for the same reason {@link #CLEAN_REVIEW} is, but the failure it guards runs the
     * other way. A reviewer that hedges costs one more round; a model that mentions this phrase in
     * passing while answering perfectly well would throw the answer away and spend a whole
     * consortium on a question that was already done. So it counts only as the entire reply.
     */
    static final String ESCALATE = "NEEDS CONSORTIUM";

    /**
     * The lines an editor puts around every merged or revised answer, alone on their own lines,
     * with the whole answer between them. The editor's reply replaces the answer outright, so a
     * reply that is only part of one must not be able to pass for it: an editor once handed back a
     * stretch from the middle of the text it was revising — opening inside a tag, every word
     * numbered, stopping mid-sentence — and that piece went out as the call's answer. A piece
     * lacks one end or the other; see {@link #editedAnswer}.
     */
    static final String ANSWER_OPEN = "<<<ANSWER";
    static final String ANSWER_CLOSE = "ANSWER>>>";

    /**
     * The word a turn starts each line with to ask for a part of the library: {@code READ 2.3}.
     * Read as strictly as {@link #CLEAN_REVIEW}: only a reply made of nothing but such lines is a
     * request to read — see {@link #readRequest}.
     */
    static final String READ = "READ";

    private static final Pattern READ_LINE = Pattern.compile("(?i)^READ\\s+(\\S+)$");

    private static final String ENVELOPE = "\n\nWrite the whole answer, from its first word to its last,"
            + " between a line containing only " + ANSWER_OPEN + " and a line containing only "
            + ANSWER_CLOSE + ", with nothing before or after them. Never a part of the answer, an"
            + " excerpt or a list of changes: what is between them replaces the answer entirely.";

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
                + "Do not describe the drafts, credit them, or mention the panel. Output only the answer."
                + ENVELOPE;
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
                + " changes. Output only the revised answer."
                + ENVELOPE;
    }

    /**
     * The single model answering on its own at {@code POST /text}, with one way out.
     *
     * <p>It is told what escalation costs, in the only terms that matter to the person paying: a
     * panel is several times the price of this call. A model given an escape hatch and no sense of
     * its cost takes it whenever the question is interesting, and "interesting" is not the bar —
     * the bar is that answering alone would produce a worse answer than a panel would, which is a
     * judgement only the model reading the request can make.
     */
    static String singleSystem() {
        return "You are answering a request on your own. Your answer is returned as it is, with no editor"
                + " and no review, so it has to stand by itself.\n\n"
                + "There is one exception. If answering this well genuinely needs several different models"
                + " — because it turns on a judgement reasonable experts would disagree about, because it"
                + " spans fields no single model is strong across, or because getting it wrong would be"
                + " costly and the request is ambiguous enough that one reading of it could be the wrong"
                + " one — then reply with exactly " + ESCALATE + " and nothing else. The same request will"
                + " then be put to a panel of models, merged and reviewed until they have no comments left."
                + "\n\n"
                + "That costs the person asking several times what this call costs, so it is not the"
                + " response to a question that is merely large, open-ended, or interesting. A long answer"
                + " you can write correctly is an answer you should write. Use it when a second and third"
                + " model would actually disagree with you, not when the work is simply substantial.\n\n"
                + "Otherwise answer the request. If it specifies a format, follow it exactly. Output only"
                + " the answer.";
    }

    static String singleTask() {
        return "Answer the request above. Output only the answer — or exactly " + ESCALATE
                + " if it needs the panel.";
    }

    /**
     * Whether a single model asked for the panel. True only when the escalation phrase is the whole
     * reply, allowing the punctuation and emphasis a model wraps such a line in — the same test
     * {@link #isClean} applies, and for the same reason: a phrase inside an answer is part of the
     * answer.
     */
    static boolean isEscalation(String reply) {
        if (reply == null) {
            return false;
        }
        String stripped = reply.trim().replaceAll("[*_`#.!\\s]+$", "").replaceAll("^[*_`#\\s]+", "");
        return stripped.toUpperCase(Locale.ROOT).equals(ESCALATE);
    }

    static String draftTask() {
        return "Answer the request above. Output only the answer.";
    }

    static String mergeTask() {
        return "Merge the drafts above into the single best answer to the request. Output only the"
                + " answer, whole, between " + ANSWER_OPEN + " and " + ANSWER_CLOSE + ".";
    }

    static String reviewTask() {
        return "Review the answer as it now stands. List what is substantively wrong with it, most serious"
                + " first, or reply with exactly " + CLEAN_REVIEW + " if there is nothing.";
    }

    static String reviseTask() {
        return "Revise the answer as it now stands to address the comments in the latest round, rejecting"
                + " any that are mistaken. Output only the revised answer, whole, between " + ANSWER_OPEN
                + " and " + ANSWER_CLOSE + ".";
    }

    /**
     * The answer inside an editor's merge or revision, or null when the reply is not exactly one
     * whole answer: {@link #ANSWER_OPEN} on the first line, {@link #ANSWER_CLOSE} on the last,
     * each once, and something between them. Strict for the reason {@link #CLEAN_REVIEW} is — a
     * reply that is nearly right is read as not right, and the answer it would have replaced
     * stands.
     */
    static String editedAnswer(String reply) {
        if (reply == null) {
            return null;
        }
        String text = reply.strip();
        if (!text.startsWith(ANSWER_OPEN + "\n") || !text.endsWith("\n" + ANSWER_CLOSE)
                || text.indexOf(ANSWER_OPEN, 1) >= 0
                || text.indexOf(ANSWER_CLOSE) != text.length() - ANSWER_CLOSE.length()) {
            return null;
        }
        String answer = text.substring(ANSWER_OPEN.length(), text.length() - ANSWER_CLOSE.length()).strip();
        return answer.isEmpty() ? null : answer;
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

    /**
     * The refs a reply asks to read, or null when the reply is not a request to read.
     *
     * <p>A reply is a request only when every non-blank line is {@code READ <ref>} — allowing the
     * emphasis and backticks a model wraps a line in, and a full stop after it — and there is at
     * least one. Strict for the reason {@link #isClean} is, with the failure running the other
     * way: a draft that happens to contain the line "READ 2" in the middle of a chapter must stay
     * a draft, and treating it as a request would throw the chapter away and pay to write it again.
     */
    static List<String> readRequest(String reply) {
        if (reply == null) {
            return null;
        }
        List<String> refs = new ArrayList<>();
        for (String raw : reply.split("\\R")) {
            String line = raw.trim().replaceAll("^[*_`#>\\-\\s]+", "").replaceAll("[*_`\\s]+$", "");
            if (line.isEmpty()) {
                continue;
            }
            Matcher matcher = READ_LINE.matcher(line);
            if (!matcher.matches()) {
                return null;
            }
            String ref = matcher.group(1).replaceAll("[*_`]+", "");
            // "READ 2.3." — the full stop ends the sentence, not the ref.
            while (ref.endsWith(".") || ref.endsWith(",") || ref.endsWith(";")) {
                ref = ref.substring(0, ref.length() - 1);
            }
            if (ref.isEmpty()) {
                return null;
            }
            if (!refs.contains(ref)) {
                refs.add(ref);
            }
        }
        return refs.isEmpty() ? null : refs;
    }

    /**
     * What every system prompt gains when the call carries a library: what the index is, and the
     * one way to read from it. Brief on purpose — it is repeated on every turn and billed each time.
     */
    static String librarySystem() {
        return "\n\nThe shared record includes the contents of the larger work this request belongs to:"
                + " every part in reading order, with its ref, title and length, the part being written"
                + " now marked. Only the index is in the record. To read the text of a written part,"
                + " reply with nothing but one line per part, in the form " + READ + " <ref> (for example"
                + " \"" + READ + " 2.3\"); its text is then added to the shared record for the whole panel"
                + " and you are asked the same thing again. Read what you actually need to get this right"
                + " — continuity, earlier definitions, what was already said — not everything. A part"
                + " already in the record does not need reading again.";
    }

    /**
     * The line added to a turn's task when a library is present: how much reading is left, or
     * that reading is closed — plus what came of this turn's last request, if it made one.
     */
    static String libraryTask(boolean readingOpen, int readsLeft, String lastReadNote) {
        StringBuilder out = new StringBuilder();
        if (lastReadNote != null && !lastReadNote.isEmpty()) {
            out.append("\n\n").append(lastReadNote);
        }
        if (readingOpen) {
            out.append("\n\nIf you need to read parts of the work first, reply with only " + READ
                    + " <ref> lines (at most ").append(readsLeft).append(" more part")
                    .append(readsLeft == 1 ? "" : "s").append(" this call); otherwise do the task.");
        } else {
            out.append("\n\nReading is closed for this turn: do the task now from what is in the record."
                    + " Do not reply with " + READ + " lines.");
        }
        return out.toString();
    }
}
