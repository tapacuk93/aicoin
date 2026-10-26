package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p>A call that carries a {@link ConsortiumLibrary library} adds two more kinds of block, and
 * both sit outside that limit: the library's contents index, and each chapter a panelist asked to
 * read. Neither is round history the panel has moved past — the index is how a turn knows what it
 * can ask for, and a chapter that was read was read because a turn needed it, so dropping it would
 * only make the next turn ask (and pay) again. They are bounded elsewhere instead: the index by
 * the library's entry limit, the reads by {@code consortium.maxLibraryReads}.
 */
final class SharedContext {

    /** One block of the record: a heading and the text under it. */
    private static final class Entry {
        private final String heading;
        private final String body;
        /** False for the request, the background and the latest answer — the parts a turn cannot do without. */
        private final boolean droppable;
        /** True for the library index and chapters read from it: never dropped, not counted. */
        private final boolean exempt;

        Entry(String heading, String body, boolean droppable) {
            this(heading, body, droppable, false);
        }

        Entry(String heading, String body, boolean droppable, boolean exempt) {
            this.heading = heading;
            this.body = body;
            this.droppable = droppable && !exempt;
            this.exempt = exempt;
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

    private SharedContext(int maxChars) {
        this.maxChars = maxChars;
    }

    /**
     * The record as plain data, for a durable call's checkpoint: every block in order, with what
     * may be dropped and what is exempt, and which block is the answer under discussion. A call
     * resumed in another process must see exactly the record the panel had, or its next turn
     * answers a different conversation.
     */
    synchronized Map<String, Object> snapshot() {
        List<Object> list = new ArrayList<>();
        for (Entry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("heading", entry.heading);
            item.put("body", entry.body);
            item.put("droppable", entry.droppable);
            item.put("exempt", entry.exempt);
            list.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", list);
        out.put("current_answer", currentAnswerIndex);
        return out;
    }

    /** The record {@link #snapshot()} described. */
    static SharedContext restore(Map<?, ?> snapshot, int maxChars) {
        SharedContext context = new SharedContext(maxChars);
        Object list = snapshot.get("entries");
        if (list instanceof List) {
            for (Object itemObj : (List<?>) list) {
                Map<?, ?> item = (Map<?, ?>) itemObj;
                context.entries.add(new Entry(String.valueOf(item.get("heading")), String.valueOf(item.get("body")),
                        Boolean.TRUE.equals(item.get("droppable")), Boolean.TRUE.equals(item.get("exempt"))));
            }
        }
        Object current = snapshot.get("current_answer");
        context.currentAnswerIndex = current instanceof Number ? ((Number) current).intValue() : -1;
        return context;
    }

    SharedContext(String request, String background, int maxChars) {
        this.maxChars = maxChars;
        entries.add(new Entry("The request", request, false));
        if (background != null && !background.trim().isEmpty()) {
            entries.add(new Entry("Background from the caller", background, false));
        }
    }

    /**
     * The library's contents index. Kept whole and outside the size limit — see the class note.
     */
    synchronized void addLibraryIndex(String index) {
        entries.add(new Entry("Contents of the work (the library) — read any written part with READ <ref>",
                index, false, true));
    }

    /**
     * A part of the library a panelist asked to read, under a heading saying which part and who
     * asked at which stage — so a later turn can see it was read, and why, without asking again.
     */
    synchronized void addLibraryRead(String heading, String text) {
        entries.add(new Entry(heading, text, false, true));
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
        int exemptChars = 0;
        for (Entry entry : entries) {
            if (entry.exempt) {
                exemptChars += entry.size();
            } else {
                total += entry.size();
            }
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
        if (rendered.length() > maxChars + exemptChars) {
            return rendered.substring(0, maxChars + exemptChars) + "\n[truncated]\n";
        }
        return rendered;
    }

    /** The record plus what this particular turn is being asked to do with it. */
    synchronized String forTurn(String task) {
        return render() + "=== Your task now ===\n" + task;
    }
}
