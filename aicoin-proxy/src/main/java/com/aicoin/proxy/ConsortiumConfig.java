package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code consortium} config block, per CONTRACT.md's "Consortium" section: whether the
 * endpoint is served at all, how many review rounds it may run, how much each turn may write, who
 * edits, and which model each panelist answers with.
 *
 * <p>Models are config rather than code for the same reason rates are: providers rename and retire
 * them on their own schedule, and a consortium that starts 502-ing because a model id lapsed
 * should be a YAML edit or an env var away from working, not a release.
 */
final class ConsortiumConfig {

    private final boolean enabled;
    private final int maxRounds;
    private final int maxOutputTokens;
    private final int maxContextChars;
    private final int leadContextChars;
    private final String editor;
    private final Map<String, String> models;
    private final Map<String, String> bestModels;
    private final int maxOutputTokensCeiling;
    private final int turnReadTimeoutSeconds;
    private final int maxLibraryChars;
    private final int maxLibraryEntries;
    private final int maxReadsPerTurn;
    private final int maxLibraryReads;
    private int callRetentionHours = 24;
    private int callLeaseSeconds = 30;
    private int pausedRetentionHours = 24;

    ConsortiumConfig(boolean enabled, int maxRounds, int maxOutputTokens, int maxContextChars,
                      int leadContextChars, String editor, Map<String, String> models) {
        this(enabled, maxRounds, maxOutputTokens, maxContextChars, leadContextChars, editor, models,
                Map.of(), Math.max(maxOutputTokens, 32000), 600, 600_000, 2000, 3, 20);
    }

    ConsortiumConfig(boolean enabled, int maxRounds, int maxOutputTokens, int maxContextChars,
                      int leadContextChars, String editor, Map<String, String> models,
                      Map<String, String> bestModels, int maxOutputTokensCeiling,
                      int turnReadTimeoutSeconds, int maxLibraryChars, int maxLibraryEntries,
                      int maxReadsPerTurn, int maxLibraryReads) {
        this.enabled = enabled;
        this.maxRounds = maxRounds;
        this.maxOutputTokens = maxOutputTokens;
        this.maxContextChars = maxContextChars;
        this.leadContextChars = leadContextChars;
        this.editor = editor == null ? "" : editor;
        this.models = models == null ? Map.of() : new LinkedHashMap<>(models);
        this.bestModels = bestModels == null ? Map.of() : new LinkedHashMap<>(bestModels);
        // A ceiling below the default would let a caller's explicit request lower nothing and
        // raise nothing, and would quietly cut the default itself; the default is the floor.
        this.maxOutputTokensCeiling = Math.max(maxOutputTokens, maxOutputTokensCeiling);
        this.turnReadTimeoutSeconds = turnReadTimeoutSeconds;
        this.maxLibraryChars = maxLibraryChars;
        this.maxLibraryEntries = maxLibraryEntries;
        this.maxReadsPerTurn = Math.max(0, maxReadsPerTurn);
        this.maxLibraryReads = Math.max(0, maxLibraryReads);
    }

    /** Whether {@code POST /consortium} is served. Off means {@code 404}, as if it did not exist. */
    boolean isEnabled() {
        return enabled;
    }

    /**
     * The most review rounds one call may run before it returns whatever it has. The cap is what
     * makes "until no more comments" terminate: reviewers can always find something, and each
     * round is one paid call per panelist.
     */
    int getMaxRounds() {
        return maxRounds;
    }

    /** Output cap sent to every panelist on every turn. */
    int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * How much of the panel's shared record one turn may be given. It is sent to every panelist on
     * every turn, so its size is multiplied by the panel and by the rounds, and every character of
     * it is billed as input. Past this, the oldest rounds are dropped — see {@link SharedContext}.
     */
    int getMaxContextChars() {
        return maxContextChars;
    }

    /**
     * How much context makes a call context-heavy enough to be led by one model rather than
     * drafted by all of them ({@code mode: "auto"}). Above this, N independent drafts are mostly N
     * re-readings of the same material, billed N times, so the lead writes and the panel improves.
     */
    int getLeadContextChars() {
        return leadContextChars;
    }

    /**
     * The panelist that merges the drafts and applies the comments. Empty means "the first
     * panelist", so a deployment that loses a provider still has an editor.
     */
    String getEditor() {
        return editor;
    }

    /**
     * A model setting as the ordered list it names. A setting may be one id or several, comma
     * separated ({@code "kimi-k3.1-pro-highspeed,kimi-k3.1-pro"}) — the YAML list form is joined
     * that way when config is read — and a turn tries them in order when one fails. See
     * {@link #modelsFor}.
     */
    static List<String> split(String setting) {
        List<String> out = new ArrayList<>();
        if (setting == null) {
            return out;
        }
        for (String part : setting.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** @return the model this provider answers with first, or null if none is configured. */
    String modelFor(String provider) {
        List<String> chain = split(models.get(provider));
        return chain.isEmpty() ? null : chain.get(0);
    }

    Map<String, String> getModels() {
        return models;
    }

    /**
     * The model a provider answers with on a {@code quality: "best"} call: its
     * {@code consortium.bestModels} entry, or — when it has none — its ordinary one. Falling back
     * rather than leaving the provider out keeps "best" from meaning "fewer": a provider whose
     * strongest model nobody has confirmed still has a voice on the panel, at the strength it
     * always had.
     */
    String modelFor(String provider, boolean best) {
        List<String> chain = modelsFor(provider, best);
        return chain.isEmpty() ? null : chain.get(0);
    }

    /**
     * Every model a provider's turn may use, in the order to try them: the first answers unless
     * it fails — overloaded, rate-limited, a 5xx, a timeout, or a reply with no text — and then the
     * next is tried within the same turn. A provider's fastest model is often its least available
     * one (Kimi's high-speed K3.1 Pro refused five tries in five as overloaded on 2026-10-26), and a
     * turn lost to that is a panelist's voice lost from a round; a list lets a deployment ask for
     * the fast one without betting the turn on it.
     */
    List<String> modelsFor(String provider, boolean best) {
        if (best) {
            List<String> chain = split(bestModels.get(provider));
            if (!chain.isEmpty()) {
                return chain;
            }
        }
        return split(models.get(provider));
    }

    Map<String, String> getBestModels() {
        return bestModels;
    }

    /**
     * The most a caller's {@code max_output_tokens} may ask each turn to write. A long answer —
     * a book chapter in HTML with MathML, from a model that spends part of its cap thinking —
     * does not fit in the default, and a turn that runs out mid-answer is worse than no turn: it
     * is paid for and then rejected. The ceiling is what bounds what one turn can cost.
     */
    int getMaxOutputTokensCeiling() {
        return maxOutputTokensCeiling;
    }

    /**
     * The per-turn output cap for a call that asked for {@code requested} (null: it did not ask).
     * Clamped to {@link #getMaxOutputTokensCeiling()}, never below one.
     */
    int outputTokensFor(Integer requested) {
        if (requested == null) {
            return maxOutputTokens;
        }
        return Math.max(1, Math.min(requested, maxOutputTokensCeiling));
    }

    /**
     * How long one consortium turn may go without the provider sending a byte. Separate from
     * {@code upstream.readTimeoutSeconds} because a turn is not streamed: the provider says
     * nothing until the whole answer is written, and a long answer from a thinking model takes
     * minutes. At the forwarding path's sixty seconds every chapter-length turn was abandoned
     * (and refunded) just before it would have arrived.
     */
    int getTurnReadTimeoutSeconds() {
        return turnReadTimeoutSeconds;
    }

    /** Most characters of chapter text a {@code library} may carry in total. */
    int getMaxLibraryChars() {
        return maxLibraryChars;
    }

    /** Most entries a {@code library} may list. */
    int getMaxLibraryEntries() {
        return maxLibraryEntries;
    }

    /**
     * How many times one turn may answer with {@code READ} lines before it is told reading is
     * closed. Each request re-runs the turn, and each re-run is a paid call.
     */
    int getMaxReadsPerTurn() {
        return maxReadsPerTurn;
    }

    /** How many chapters one call may add to the shared record, across every turn. */
    int getMaxLibraryReads() {
        return maxLibraryReads;
    }

    /**
     * How long a call with a {@code call_id} is kept in Redis — its progress while it runs, its
     * events and its result after — so a client that lost it (an app frozen in the background, a
     * proxy restart) can come back for it. Long enough for a phone left overnight.
     */
    int getCallRetentionHours() {
        return callRetentionHours;
    }

    /**
     * How long a process's claim on a running call lasts without being renewed. A process that
     * dies stops renewing; once this runs out another one — or the same one, restarted — resumes
     * the call. Short enough that a redeploy loses seconds, long enough that a slow Redis round
     * trip does not hand a healthy call to a second owner.
     */
    int getCallLeaseSeconds() {
        return callLeaseSeconds;
    }

    /**
     * How long a paused call is kept for its caller to resume; after it the call is deleted and a
     * request for it is answered {@code 410}, so the client starts afresh.
     */
    int getPausedRetentionHours() {
        return pausedRetentionHours;
    }

    ConsortiumConfig withPausedRetention(int hours) {
        this.pausedRetentionHours = Math.max(1, hours);
        return this;
    }

    ConsortiumConfig withCalls(int retentionHours, int leaseSeconds) {
        this.callRetentionHours = Math.max(1, retentionHours);
        this.callLeaseSeconds = Math.max(5, leaseSeconds);
        return this;
    }
}
