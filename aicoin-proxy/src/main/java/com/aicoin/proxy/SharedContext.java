package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;

/**
 * The record of a consortium call that every panelist sees, per CONTRACT.md's "Consortium"
 * section: the request, whatever background the caller supplied, the drafts, each answer the
 * editor produced, and every round of comments — in the order they happened.
 *
 * <p>A panel with no shared context is not a panel, it is a queue of strangers. Reviewers who
 * cannot see the previous round raise the same objection twice and cannot tell whether the editor
 * addressed the last one; the editor cannot tell a comment it has already answered from a new one;
 * and none of them can see the caller's background. Rounds then converge by luck rather than by
 * argument, which is exactly what makes a call run to the cap.
 *
 * <p>The one thing it must not do is grow without limit: the whole context goes into every turn of
 * every round, so its size is multiplied by the panel size and by the rounds, and each turn's input
 * is billed. Past {@code maxChars} the oldest <em>rounds</em> are dropped, in order, and their
 * absence is stated in the text; the request, the caller's background and the current answer are
 * never dropped, because a turn without those is not answerable at all.
 */
final class SharedContext {

    /** One block of the record: a heading and the text under it. */
    private static final class Entry {
        private final String heading;
        private final String body;
        /** False for the request, the background and the latest answer — the parts a turn cannot do without. */
        private final boolean droppable;

        Entry(String heading, String body, boolean droppable) {
            this.heading = heading;
            this.body = body;
            this.droppable = droppable;
        }

        int size() {
            return heading.length() + body.length() + 12;
        }

        String render() {
            return "=== " + heading + " ===\n" + body + "\n\n";
        }
    }

    private static final String OMITTED = "=== [earlier rounds omitted to stay within the context limit] ===\n\n";

    private final int maxChars;
    private final List<Entry> entries = new ArrayList<>();
    /** Index of the entry holding the answer under discussion, so a new one can supersede it. */
    private int currentAnswerIndex = -1;

    SharedContext(String request, String background, int maxChars) {
        this.maxChars = maxChars;
        entries.add(new Entry("The request", request, false));
        if (background != null && !background.trim().isEmpty()) {
            entries.add(new Entry("Background from the caller", background, false));
        }
    }

    /** The drafts, labelled by panelist. Droppable: once merged, they are history. */
    synchronized void addDrafts(List<String> providers, List<String> drafts) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < drafts.size(); i++) {
            body.append("--- ").append(ChatAdapter.displayName(providers.get(i))).append(" ---\n")
                    .append(drafts.get(i)).append("\n");
        }
        entries.add(new Entry("Independent drafts from the panel", body.toString().trim(), true));
    }

    /**
     * A new answer from the editor. The previous one becomes droppable history — only the answer
     * now on the table is protected, since that is what the next turn is about.
     */
    synchronized void addAnswer(String heading, String answer) {
        if (currentAnswerIndex >= 0) {
            Entry previous = entries.get(currentAnswerIndex);
            entries.set(currentAnswerIndex, new Entry(previous.heading, previous.body, true));
        }
        entries.add(new Entry(heading, answer, false));
        currentAnswerIndex = entries.size() - 1;
    }

    /** One round's comments, labelled by reviewer. Reviewers who cleared it are named as having cleared it. */
    synchronized void addReviews(int round, List<String> providers, List<String> comments, List<String> cleared) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < comments.size(); i++) {
            body.append("--- ").append(ChatAdapter.displayName(providers.get(i))).append(" ---\n")
                    .append(comments.get(i)).append("\n");
        }
        if (!cleared.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String provider : cleared) {
                names.add(ChatAdapter.displayName(provider));
            }
            body.append("--- ").append(String.join(", ", names))
                    .append(cleared.size() == 1 ? " had" : " had").append(" no comments ---\n");
        }
        entries.add(new Entry("Round " + round + " review", body.toString().trim(), true));
    }

    /**
     * The record as one block of text, with the oldest droppable entries omitted if it would
     * otherwise run past {@code maxChars}.
     */
    synchronized String render() {
        boolean[] dropped = new boolean[entries.size()];
        int total = OMITTED.length();
        for (Entry entry : entries) {
            total += entry.size();
        }
        // Oldest first: the earliest rounds are the ones the panel has already moved past.
        for (int i = 0; i < entries.size() && total > maxChars; i++) {
            if (entries.get(i).droppable) {
                dropped[i] = true;
                total -= entries.get(i).size();
            }
        }
        StringBuilder out = new StringBuilder();
        boolean anyDropped = false;
        for (int i = 0; i < entries.size(); i++) {
            if (dropped[i]) {
                anyDropped = true;
                continue;
            }
            if (anyDropped) {
                out.append(OMITTED);
                anyDropped = false;
            }
            out.append(entries.get(i).render());
        }
        String rendered = out.toString();
        // Everything left is undroppable and still too long — a single enormous request or answer.
        // Truncating the tail keeps the turn answerable rather than failing it upstream on a
        // context-length error.
        if (rendered.length() > maxChars) {
            return rendered.substring(0, maxChars) + "\n[truncated]\n";
        }
        return rendered;
    }

    /** The record plus what this particular turn is being asked to do with it. */
    synchronized String forTurn(String task) {
        return render() + "=== Your task now ===\n" + task;
    }
}
