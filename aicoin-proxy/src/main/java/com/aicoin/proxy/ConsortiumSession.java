package com.aicoin.proxy;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One consortium call, per CONTRACT.md's "Consortium" section — draft, merge, review, revise —
 * and, when it was given a {@code call_id}, durable: see "Durable calls".
 *
 * <p>Every provider turn completes on some Netty event loop, so all state goes through this
 * object's lock; the per-stage sets of who has finished are what decide when a stage is over, and
 * losing one would hang the call.
 *
 * <p><b>Why a call is durable.</b> A chapter is ten minutes and dozens of coins of turns, and it
 * used to die with whatever carried it: the phone freezing the app in the background dropped the
 * stream and the call stopped at {@code client_gone}; a redeploy killed every call in flight. The
 * caller then started over and paid again. So a call with an id no longer belongs to a
 * connection. It runs to its end whoever is listening, and it is checkpointed to Redis after every
 * turn — the record the panel sees, who has finished each stage, what was charged — so that a
 * process that dies leaves behind everything another one needs to carry on from the last
 * completed turn. A turn that was in flight is done again; one that completed is never redone and
 * never billed twice.
 *
 * <p>Stages are written to be resumable for the same reason: each one runs only the panelists who
 * have not finished it yet, and the changes a stage makes to the record are checkpointed together
 * with the move to the next stage, so a resume never applies them twice.
 */
final class ConsortiumSession {

    private static final Logger LOG = Logger.getLogger(ConsortiumSession.class.getName());

    /** The hold placed before each turn — the same one coin every other paid call holds. */
    static final double CALL_COST_AICOIN = 1.0;

    /** How much of a review's comments a {@code turn} event carries; the result has them whole. */
    static final int COMMENT_PREVIEW_CHARS = 400;

    /** Why an editor's reply is not usable as the answer, or null when it is. */
    private static final Function<String, String> WHOLE_ANSWER =
            text -> ConsortiumPrompts.editedAnswer(text) == null ? "reply was not one whole answer" : null;

    // ---- What the call is ----------------------------------------------------------------------

    private final ProxyConfig config;
    private final EventLoopGroup group;
    private final ProviderHealthTracker healthTracker;
    private final AicoinLedger ledger;
    private final String wallet;
    /** The caller's id for this call, or null for a call that lives and dies with its connection. */
    private final String callId;
    private final String storeKey;
    private final ConsortiumStore store;
    private final List<String> panel;
    /**
     * Who merges and revises. Not fixed: when the editor cannot do its turn — out of credit, an
     * error, a timeout, a reply that is not one whole answer — the turn goes to the next-ranked
     * panelist that can (see {@link #editorTurn}), and that panelist edits from then on.
     */
    private String editor;
    /** Providers left off the panel because their accounts cannot pay, as JSON objects. */
    private final List<String> skippedJson = new ArrayList<>();
    /**
     * Provider -> the models its turns may use, in the order to try them (see
     * {@link ConsortiumConfig#modelsFor}); fixed when the call starts so a resumed call speaks to
     * the same models.
     */
    private final Map<String, List<String>> models;
    /**
     * Provider -> where in its list to start: the model that last answered for it. Within one
     * call a model that just refused as overloaded is likely to refuse again a minute later, and
     * each refusal costs the turn a round trip, so a call that fell back stays fallen back.
     */
    private final Map<String, Integer> preferred = new HashMap<>();
    /** Provider -> the model that last actually answered for it. */
    private final Map<String, String> answeredBy = new LinkedHashMap<>();
    /** The model that wrote each draft, parallel to {@link #drafts}. */
    private final List<String> draftModels = new ArrayList<>();
    private final int maxRounds;
    private final boolean includeTranscript;
    /** Everybody answers once and nothing is merged — see the note in {@link ConsortiumHandler}. */
    private final boolean poll;
    /** True when one model drafts and the rest improve it. */
    private final boolean lead;
    private final String subject;
    private final String escalatedFrom;
    private final boolean best;
    private final int maxOutputTokens;
    /** The work this request belongs to, or null — see {@link ConsortiumLibrary}. */
    private final ConsortiumLibrary library;
    private final SharedContext context;
    private final UpstreamTimer timer;

    // ---- Where it has got to (all checkpointed) -------------------------------------------------

    /** draft, merge, review, revise or done. */
    private String phase = "draft";
    private int round;
    private final List<String> draftProviders = new ArrayList<>();
    private final List<String> drafts = new ArrayList<>();
    /** Drafters who have had their turn, answered or failed: a resume does not ask them again. */
    private final Set<String> draftsFinished = new LinkedHashSet<>();
    private final List<String> reviewJson = new ArrayList<>();
    private final List<String> errorJson = new ArrayList<>();
    private final List<String> readJson = new ArrayList<>();
    /** Ref -> who asked for it and when, for every library part already in the record. */
    private final Map<String, String> readBy = new HashMap<>();
    private int libraryReads;
    private String answer;
    private int calls;
    private long coinsCharged;
    /**
     * Coins charged per provider. The total is what the wallet paid, but a caller deciding which
     * model to lean on wants to know which of them the money went to — and only this side knows,
     * since each turn is settled against its own provider's reported usage.
     */
    private final Map<String, Long> coinsByProvider = new LinkedHashMap<>();
    private boolean insufficient;
    private String stoppedReason = "clean";
    private final List<String> commentProviders = new ArrayList<>();
    private final List<String> comments = new ArrayList<>();
    private final List<String> clearedBy = new ArrayList<>();
    /** Reviewers of the current round who have had their turn. */
    private final Set<String> reviewersFinished = new LinkedHashSet<>();
    private int reviseCommentCount;
    /**
     * Providers holding a coin for a turn still in flight. A process that dies mid-turn leaves
     * the hold taken and the turn unanswered; the process that resumes the call gives those coins
     * back, since it is about to run — and bill — that turn again.
     */
    private final List<String> pendingHolds = new ArrayList<>();

    // ---- What it has said ------------------------------------------------------------------------

    private int seq;
    private final List<String> events = new ArrayList<>();
    private boolean finished;
    private int finalStatus;
    private String finalBody;
    private String finalLine;

    private final List<ConsortiumClient> clients = new ArrayList<>();
    /** The caller's {@code notify}: topic, title, body, data, failure_title, failure_body. */
    private Map<String, Object> notify;
    private boolean notified;
    /** What each device's push came to, once sent. */
    private final List<String> pushResults = new ArrayList<>();
    /** Only for a call without an id: its one connection went away, so the rounds stop. */
    private boolean aborted;
    private Runnable onFinished = () -> { };

    ConsortiumSession(ProxyConfig config, EventLoopGroup group, ProviderHealthTracker healthTracker,
                      AicoinLedger ledger, String wallet, String callId, ConsortiumStore store,
                      String prompt, String background, List<String> panel, String editor,
                      Map<String, List<String>> models, int maxRounds, boolean includeTranscript, boolean lead,
                      boolean poll, String subject, String escalatedFrom, boolean best, int maxOutputTokens,
                      ConsortiumLibrary library, UpstreamTimer timer,
                      int priorCalls, long priorCoins, Map<String, Long> priorSpend) {
        this(config, group, healthTracker, ledger, wallet, callId, store, panel, editor, models, maxRounds,
                includeTranscript, lead, poll, subject, escalatedFrom, best, maxOutputTokens, library, timer,
                new SharedContext(prompt, background, config.getConsortium().getMaxContextChars()));
        this.calls = priorCalls;
        this.coinsCharged = priorCoins;
        this.coinsByProvider.putAll(priorSpend);
        if (library != null) {
            context.addLibraryIndex(library.renderIndex());
        }
    }

    private ConsortiumSession(ProxyConfig config, EventLoopGroup group, ProviderHealthTracker healthTracker,
                              AicoinLedger ledger, String wallet, String callId, ConsortiumStore store,
                              List<String> panel, String editor, Map<String, List<String>> models, int maxRounds,
                              boolean includeTranscript, boolean lead, boolean poll, String subject,
                              String escalatedFrom, boolean best, int maxOutputTokens,
                              ConsortiumLibrary library, UpstreamTimer timer, SharedContext context) {
        this.config = config;
        this.group = group;
        this.healthTracker = healthTracker;
        this.ledger = ledger;
        this.wallet = wallet;
        this.callId = callId;
        this.store = callId == null ? null : store;
        this.storeKey = callId == null ? null : ConsortiumStore.key(wallet, callId);
        this.panel = List.copyOf(panel);
        this.editor = editor;
        this.models = new LinkedHashMap<>();
        models.forEach((provider, chain) -> this.models.put(provider, List.copyOf(chain)));
        this.maxRounds = maxRounds;
        this.includeTranscript = includeTranscript;
        this.lead = lead;
        this.poll = poll;
        this.subject = subject;
        this.escalatedFrom = escalatedFrom;
        this.best = best;
        this.maxOutputTokens = maxOutputTokens;
        this.library = library;
        this.timer = timer;
        this.context = context;
    }

    String storeKey() {
        return storeKey;
    }

    void onFinished(Runnable action) {
        this.onFinished = action;
    }

    // ---- Persistence -----------------------------------------------------------------------------

    /** Everything a process needs to carry this call on, as plain data. */
    synchronized Map<String, Object> snapshot() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("wallet", wallet);
        state.put("call_id", callId);
        state.put("panel", new ArrayList<>(panel));
        state.put("editor", editor);
        state.put("skipped", new ArrayList<>(skippedJson));
        state.put("models", new LinkedHashMap<>(models));
        state.put("preferred", new LinkedHashMap<>(preferred));
        state.put("answered_by", new LinkedHashMap<>(answeredBy));
        state.put("draft_models", new ArrayList<>(draftModels));
        state.put("max_rounds", maxRounds);
        state.put("include_transcript", includeTranscript);
        state.put("lead", lead);
        state.put("poll", poll);
        state.put("subject", subject);
        state.put("best", best);
        state.put("max_output_tokens", maxOutputTokens);
        state.put("context", context.snapshot());
        state.put("phase", phase);
        state.put("round", round);
        state.put("draft_providers", new ArrayList<>(draftProviders));
        state.put("drafts", new ArrayList<>(drafts));
        state.put("drafts_finished", new ArrayList<>(draftsFinished));
        state.put("reviews", new ArrayList<>(reviewJson));
        state.put("errors", new ArrayList<>(errorJson));
        state.put("reads", new ArrayList<>(readJson));
        state.put("read_by", new LinkedHashMap<>(readBy));
        state.put("library_reads", libraryReads);
        state.put("answer", answer);
        state.put("calls", calls);
        state.put("coins_charged", coinsCharged);
        state.put("spend", new LinkedHashMap<>(coinsByProvider));
        state.put("insufficient", insufficient);
        state.put("stopped_reason", stoppedReason);
        state.put("comment_providers", new ArrayList<>(commentProviders));
        state.put("comments", new ArrayList<>(comments));
        state.put("cleared_by", new ArrayList<>(clearedBy));
        state.put("reviewers_finished", new ArrayList<>(reviewersFinished));
        state.put("revise_comment_count", reviseCommentCount);
        state.put("pending_holds", new ArrayList<>(pendingHolds));
        state.put("seq", seq);
        state.put("finished", finished);
        state.put("final_status", finalStatus);
        state.put("final_body", finalBody);
        state.put("final_line", finalLine);
        state.put("notify", notify);
        state.put("notified", notified);
        state.put("push_results", new ArrayList<>(pushResults));
        return state;
    }

    /** Writes the checkpoint. Issued under the lock, so checkpoints reach Redis in order. */
    private synchronized void checkpoint() {
        if (store != null) {
            store.checkpoint(storeKey, Json.write(snapshot()));
        }
    }

    /**
     * A call picked up from its last checkpoint by a process that did not start it — or by the
     * same one, restarted.
     *
     * @param requestBody the body that started it, for the library (kept out of the checkpoint so
     *                    the largest part of a call is written once, not after every turn)
     */
    static ConsortiumSession restore(Map<?, ?> state, List<String> storedEvents, String requestBody,
                                     ProxyConfig config, EventLoopGroup group, ProviderHealthTracker healthTracker,
                                     AicoinLedger ledger, ConsortiumStore store) {
        ConsortiumLibrary library = null;
        try {
            Object request = Json.load(requestBody);
            if (request instanceof Map) {
                library = ConsortiumLibrary.parse(((Map<?, ?>) request).get("library"),
                        Integer.MAX_VALUE, Integer.MAX_VALUE);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "resumed consortium call has an unreadable library", e);
        }
        Map<String, List<String>> models = new LinkedHashMap<>();
        ((Map<?, ?>) state.get("models")).forEach((k, v) -> models.put(String.valueOf(k),
                v instanceof List ? strings(v) : ConsortiumConfig.split(String.valueOf(v))));
        ConsortiumSession session = new ConsortiumSession(config, group, healthTracker, ledger,
                str(state.get("wallet")), str(state.get("call_id")), store, strings(state.get("panel")),
                str(state.get("editor")), models, num(state.get("max_rounds")),
                Boolean.TRUE.equals(state.get("include_transcript")), Boolean.TRUE.equals(state.get("lead")),
                Boolean.TRUE.equals(state.get("poll")), str(state.get("subject")), null,
                Boolean.TRUE.equals(state.get("best")), num(state.get("max_output_tokens")), library,
                UpstreamTimer.NONE,
                SharedContext.restore((Map<?, ?>) state.get("context"), config.getConsortium().getMaxContextChars()));
        session.phase = str(state.get("phase"));
        session.round = num(state.get("round"));
        session.draftProviders.addAll(strings(state.get("draft_providers")));
        session.skippedJson.addAll(strings(state.get("skipped")));
        session.draftModels.addAll(strings(state.get("draft_models")));
        if (state.get("preferred") instanceof Map) {
            ((Map<?, ?>) state.get("preferred")).forEach((k, v) -> session.preferred.put(str(k), num(v)));
        }
        if (state.get("answered_by") instanceof Map) {
            ((Map<?, ?>) state.get("answered_by")).forEach((k, v) -> session.answeredBy.put(str(k), str(v)));
        }
        session.drafts.addAll(strings(state.get("drafts")));
        session.draftsFinished.addAll(strings(state.get("drafts_finished")));
        session.reviewJson.addAll(strings(state.get("reviews")));
        session.errorJson.addAll(strings(state.get("errors")));
        session.readJson.addAll(strings(state.get("reads")));
        if (state.get("read_by") instanceof Map) {
            ((Map<?, ?>) state.get("read_by")).forEach((k, v) -> session.readBy.put(str(k), str(v)));
        }
        session.libraryReads = num(state.get("library_reads"));
        session.answer = str(state.get("answer"));
        session.calls = num(state.get("calls"));
        session.coinsCharged = ((Number) state.get("coins_charged")).longValue();
        if (state.get("spend") instanceof Map) {
            ((Map<?, ?>) state.get("spend")).forEach((k, v) ->
                    session.coinsByProvider.put(str(k), ((Number) v).longValue()));
        }
        session.insufficient = Boolean.TRUE.equals(state.get("insufficient"));
        session.stoppedReason = str(state.get("stopped_reason"));
        session.commentProviders.addAll(strings(state.get("comment_providers")));
        session.comments.addAll(strings(state.get("comments")));
        session.clearedBy.addAll(strings(state.get("cleared_by")));
        session.reviewersFinished.addAll(strings(state.get("reviewers_finished")));
        session.reviseCommentCount = num(state.get("revise_comment_count"));
        session.pendingHolds.addAll(strings(state.get("pending_holds")));
        if (state.get("notify") instanceof Map) {
            Map<String, Object> notify = new LinkedHashMap<>();
            ((Map<?, ?>) state.get("notify")).forEach((k, v) -> notify.put(String.valueOf(k), v));
            session.notify = notify;
        }
        session.notified = Boolean.TRUE.equals(state.get("notified"));
        session.events.addAll(storedEvents);
        // The events list can run ahead of the checkpoint (a "started" goes out before the turn's
        // result is checkpointed), so numbering carries on from whichever is further.
        int lastStored = storedEvents.isEmpty() ? 0 : seqOf(storedEvents.get(storedEvents.size() - 1));
        session.seq = Math.max(num(state.get("seq")), lastStored);
        return session;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int num(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /** The {@code seq} of a stored event line, or 0. */
    static int seqOf(String line) {
        try {
            Object parsed = Json.load(line);
            if (parsed instanceof Map && ((Map<?, ?>) parsed).get("seq") instanceof Number) {
                return ((Number) ((Map<?, ?>) parsed).get("seq")).intValue();
            }
        } catch (Exception e) {
            // An unreadable line has no number; numbering carries on from the checkpoint.
        }
        return 0;
    }

    // ---- Clients ---------------------------------------------------------------------------------

    /**
     * Adds a connection to this call: replays every event it has not seen, then — if the call is
     * still running — streams the rest as they happen; if it is over, ends with the outcome.
     * Leaving does not stop a call with an id; it stops one without (see {@link #aborted}).
     */
    synchronized void attach(ChannelHandlerContext ctx, boolean stream) {
        attach(new ConsortiumClient(ctx, stream));
    }

    /** As above, for a client that may already have been sent part of the call. */
    synchronized void attach(ConsortiumClient client) {
        ChannelHandlerContext ctx = client.ctx;
        if (finished) {
            replayTo(client);
            return;
        }
        client.begin();
        for (String line : events) {
            client.event(seqOf(line), line);
        }
        clients.add(client);
        ChannelFutureListener gone = f -> clientGone(client);
        ctx.channel().closeFuture().addListener(gone);
    }

    private synchronized void replayTo(ConsortiumClient client) {
        client.begin();
        for (String line : events) {
            int n = seqOf(line);
            if (n < seq) {
                client.event(n, line);
            }
        }
        client.end(finalStatus, finalBody, seq, finalLine, coinsCharged);
    }

    /**
     * A finished call, straight from what was stored — for a client coming back after the process
     * that ran it is gone. Charges nothing: it only reads.
     */
    static void replayStored(ConsortiumClient client, Map<?, ?> state, List<String> events) {
        client.begin();
        int last = num(state.get("seq"));
        for (String line : events) {
            int n = seqOf(line);
            if (n < last) {
                client.event(n, line);
            }
        }
        client.end(num(state.get("final_status")), str(state.get("final_body")), last,
                str(state.get("final_line")), ((Number) state.get("coins_charged")).longValue());
    }

    private synchronized void clientGone(ConsortiumClient client) {
        client.closed();
        clients.remove(client);
        if (callId == null && !finished) {
            // A call without an id is its connection's: a client that gave up is nobody to spend
            // the next round's coins for, so the rounds stop at the next boundary — the turns
            // already in flight are paid for and cannot be recalled.
            aborted = true;
        }
    }

    private synchronized boolean isAborted() {
        return aborted;
    }

    /**
     * One event: numbered, kept (in memory and, for a durable call, in Redis), and sent to every
     * streamed client attached. Numbered in the order they happened, which is also the order every
     * client receives them — so a client that comes back can drop what it has already shown.
     */
    private synchronized void emit(String name, String fields) {
        seq++;
        String line = "{\"event\":" + Json.string(name) + ",\"seq\":" + seq
                + (fields.isEmpty() ? "" : "," + fields) + "}";
        events.add(line);
        if (store != null) {
            store.appendEvent(storeKey, line);
        }
        for (ConsortiumClient client : new ArrayList<>(clients)) {
            client.event(seq, line);
        }
    }

    // ---- Running -----------------------------------------------------------------------------------

    /** A new call: its first event, then the drafts. */
    void start(ChannelHandlerContext ctx, boolean stream) {
        synchronized (this) {
            if (ctx != null) {
                attach(ctx, stream);
            }
            emit("start", startFields(false));
            checkpoint();
        }
        beginDrafts();
    }

    /** The initial checkpoint, for {@link ConsortiumStore#create}. */
    synchronized String initialState() {
        return Json.write(snapshot());
    }

    /**
     * Records a provider kept off the panel because its account cannot pay, for the start event
     * and the result: a caller that asked for the whole panel should be told it did not get it,
     * and why.
     */
    synchronized void skipped(String provider, String reason) {
        skippedJson.add("{\"provider\":" + Json.string(provider) + ",\"reason\":" + Json.string(reason) + "}");
    }

    private String startFields(boolean resumed) {
        StringBuilder start = new StringBuilder("\"mode\":").append(Json.string(modeName()))
                .append(",\"poll\":").append(poll)
                .append(",\"panel\":").append(ConsortiumHandler.stringList(panel))
                .append(",\"editor\":").append(Json.string(editor()))
                .append(",\"subject\":").append(Json.string(subject))
                .append(",\"quality\":").append(Json.string(best ? "best" : "standard"))
                .append(",\"models\":").append(modelsJson())
                .append(",\"max_output_tokens\":").append(maxOutputTokens)
                .append(",\"max_rounds\":").append(maxRounds);
        if (!skippedJson.isEmpty()) {
            start.append(",\"skipped\":[").append(String.join(",", skippedJson)).append("]");
        }
        if (callId != null) {
            start.append(",\"call_id\":").append(Json.string(callId));
        }
        if (resumed) {
            start.append(",\"resumed\":true");
        }
        return start.toString();
    }

    /**
     * Carries a restored call on from where its checkpoint left it. Coins held for turns that were
     * in flight are given back first — those turns are about to run, and be billed, again — then
     * the call says it was resumed and continues with whatever its stage still needs.
     */
    void resume() {
        String from;
        int fromRound;
        synchronized (this) {
            for (String provider : pendingHolds) {
                ledger.refund(wallet, CALL_COST_AICOIN, provider);
                calls--;
            }
            pendingHolds.clear();
            from = phase;
            fromRound = round;
            // The start event says so too, for a client that re-attaches from the beginning.
            if (!events.isEmpty() && events.get(0).startsWith("{\"event\":\"start\"")
                    && !events.get(0).contains("\"resumed\":true")) {
                String marked = events.get(0).substring(0, events.get(0).length() - 1) + ",\"resumed\":true}";
                events.set(0, marked);
                if (store != null) {
                    store.replaceEvent(storeKey, 0, marked);
                }
            }
            emit("resumed", "\"from\":" + Json.string(from) + ",\"round\":" + fromRound);
            checkpoint();
        }
        LOG.info("consortium " + storeKey + " resumed at " + from + " round " + fromRound);
        switch (from) {
            case "draft":
                beginDrafts();
                break;
            case "merge":
                startMerge();
                break;
            case "review":
                runReviews();
                break;
            case "revise":
                startRevise();
                break;
            default:
                finishOk();
        }
    }

    /**
     * The model this provider's next turn starts with — or, once it has answered, the one that
     * did. What events, {@code models} and the transcript report: the model that wrote the words.
     */
    private synchronized String modelFor(String provider) {
        String answered = answeredBy.get(provider);
        if (answered != null) {
            return answered;
        }
        List<String> chain = models.get(provider);
        return chain == null || chain.isEmpty() ? null : chain.get(Math.min(preferred.getOrDefault(provider, 0), chain.size() - 1));
    }

    private String modeName() {
        return lead ? "lead" : "panel";
    }

    private String modelsJson() {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < panel.size(); i++) {
            json.append(i == 0 ? "" : ",").append(Json.string(panel.get(i))).append(":")
                    .append(Json.string(modelFor(panel.get(i))));
        }
        return json.append("}").toString();
    }

    private String turnFields(String stage, int round, String provider, String model, String status) {
        return "\"stage\":" + Json.string(stage) + ",\"round\":" + round
                + ",\"provider\":" + Json.string(provider) + ",\"model\":" + Json.string(model)
                + ",\"status\":" + Json.string(status);
    }

    /** Who writes the first answer: the whole panel, or — in lead mode — the editor alone. */
    private List<String> draftPanel() {
        return lead ? List.of(editor()) : panel;
    }

    private void beginDrafts() {
        List<String> drafters;
        synchronized (this) {
            phase = "draft";
            drafters = new ArrayList<>(draftPanel());
            drafters.removeAll(draftsFinished);
        }
        if (drafters.isEmpty()) {
            afterDrafts();
            return;
        }
        String system = poll ? ConsortiumPrompts.pollSystem()
                : lead ? ConsortiumPrompts.leadDraftSystem() : ConsortiumPrompts.draftSystem();
        String task = poll ? ConsortiumPrompts.pollTask()
                : lead ? ConsortiumPrompts.leadDraftTask() : ConsortiumPrompts.draftTask();
        for (String provider : drafters) {
            turn(provider, system, task, "draft", 0, null, (text, error) -> {
                boolean last;
                synchronized (this) {
                    if (text != null) {
                        draftProviders.add(provider);
                        drafts.add(text);
                        draftModels.add(answeredBy.get(provider));
                    }
                    draftsFinished.add(provider);
                    last = draftsFinished.containsAll(draftPanel());
                    checkpoint();
                }
                if (lead && text == null && !insufficient()) {
                    // The lead could not write the answer at all. Nothing exists yet for anyone
                    // to merge or review, so the call would end with no answer: hand the lead to
                    // the next panelist that can pay instead.
                    String next = nextEditor(draftsFinished, false);
                    if (next != null) {
                        changeEditor(provider, next, error, "draft", 0);
                        beginDrafts();
                        return;
                    }
                }
                if (last) {
                    afterDrafts();
                }
            });
        }
    }

    private void afterDrafts() {
        List<String> providersSnapshot;
        List<String> draftsSnapshot;
        synchronized (this) {
            providersSnapshot = new ArrayList<>(draftProviders);
            draftsSnapshot = new ArrayList<>(drafts);
        }
        if (draftsSnapshot.isEmpty()) {
            // Nobody answered: either every provider failed, or the wallet could not pay for the
            // first turn. The wallet case is the one the client can act on.
            if (insufficient()) {
                sendInsufficient();
            } else {
                finish(HttpResponseStatus.BAD_GATEWAY, "no panelist answered");
            }
            return;
        }
        if (poll) {
            // Every answer is the result. There is nothing to merge and nothing to review:
            // reviewing would ask the panel to reconcile judgements the caller asked to see
            // unreconciled, and that is the one thing a poll must not do.
            stoppedReason("polled");
            finishOk();
            return;
        }
        if (draftsSnapshot.size() == 1) {
            // One draft is already the merged answer; a merge turn here would only cost a call
            // to rewrite a single input.
            synchronized (this) {
                answer = draftsSnapshot.get(0);
                context.addAnswer("The answer as it now stands", draftsSnapshot.get(0));
            }
            beginReviewRound();
            return;
        }
        synchronized (this) {
            if (!lead) {
                context.addDrafts(providersSnapshot, draftsSnapshot);
            }
            phase = "merge";
            checkpoint();
        }
        startMerge();
    }

    private void startMerge() {
        List<String> draftsSnapshot;
        synchronized (this) {
            draftsSnapshot = new ArrayList<>(drafts);
        }
        editorTurn(ConsortiumPrompts.mergeSystem(draftsSnapshot.size()),
                ConsortiumPrompts.mergeTask(), "merge", 0, new LinkedHashSet<>(),
                (text, error) -> {
                    // An editor that failed to merge must not sink the call: the first draft is a
                    // real answer to the request, and the review rounds still run over it. A merge
                    // that is not one whole answer (see editedAnswer) counts as failed. Reached
                    // only once every panelist that could have taken the merge over has failed it.
                    String edited = ConsortiumPrompts.editedAnswer(text);
                    if (text != null && edited == null) {
                        recordError("merge", editor(), "reply was not one whole answer");
                    }
                    String merged = edited != null ? edited : draftsSnapshot.get(0);
                    synchronized (this) {
                        answer = merged;
                        context.addAnswer("The answer as it now stands", merged);
                    }
                    beginReviewRound();
                });
    }

    private void beginReviewRound() {
        if (isAborted()) {
            stoppedReason("client_gone");
            finishOk();
            return;
        }
        if (insufficient()) {
            stoppedReason("insufficient_balance");
            finishOk();
            return;
        }
        synchronized (this) {
            round++;
            phase = "review";
            reviewersFinished.clear();
            commentProviders.clear();
            comments.clear();
            clearedBy.clear();
            checkpoint();
        }
        runReviews();
    }

    private void runReviews() {
        List<String> reviewers;
        int thisRound;
        synchronized (this) {
            reviewers = new ArrayList<>(panel);
            reviewers.removeAll(reviewersFinished);
            thisRound = round;
        }
        if (reviewers.isEmpty()) {
            afterReviewRound();
            return;
        }
        // Every reviewer in a round reviews the same record, which is what makes their comments
        // comparable — and what lets the editor tell agreement from one model having seen
        // something the others did not. (A reviewer that reads part of the library first sees
        // that part too, and so does anyone who starts after it was added.)
        String system = ConsortiumPrompts.reviewSystem();
        String task = ConsortiumPrompts.reviewTask();
        for (String provider : reviewers) {
            turn(provider, system, task, "review", thisRound, null, (text, error) -> {
                boolean clean = text != null && ConsortiumPrompts.isClean(text);
                boolean last;
                synchronized (this) {
                    if (text != null) {
                        reviewJson.add("{\"round\":" + thisRound
                                + ",\"provider\":" + Json.string(provider)
                                + ",\"clean\":" + clean
                                + ",\"comments\":" + (clean ? "null" : Json.string(text)) + "}");
                        if (clean) {
                            clearedBy.add(provider);
                        } else {
                            commentProviders.add(provider);
                            comments.add(text);
                        }
                    }
                    reviewersFinished.add(provider);
                    last = reviewersFinished.containsAll(panel);
                    checkpoint();
                }
                if (last) {
                    afterReviewRound();
                }
            });
        }
    }

    private void afterReviewRound() {
        List<String> providersSnapshot;
        List<String> commentsSnapshot;
        List<String> clearedSnapshot;
        int thisRound;
        synchronized (this) {
            providersSnapshot = new ArrayList<>(commentProviders);
            commentsSnapshot = new ArrayList<>(comments);
            clearedSnapshot = new ArrayList<>(clearedBy);
            thisRound = round;
        }
        if (commentsSnapshot.isEmpty()) {
            // A whole round with nothing to say: this is what the call was asking for.
            stoppedReason("clean");
            finishOk();
            return;
        }
        if (thisRound >= maxRounds) {
            stoppedReason("round_limit");
            finishOk();
            return;
        }
        if (isAborted() || insufficient()) {
            stoppedReason(isAborted() ? "client_gone" : "insufficient_balance");
            finishOk();
            return;
        }
        synchronized (this) {
            // Checkpointed together with the move to revise, so a resume never adds a round twice.
            context.addReviews(thisRound, providersSnapshot, commentsSnapshot, clearedSnapshot);
            phase = "revise";
            reviseCommentCount = commentsSnapshot.size();
            checkpoint();
        }
        startRevise();
    }

    private void startRevise() {
        int thisRound;
        int commentCount;
        synchronized (this) {
            thisRound = round;
            commentCount = reviseCommentCount;
        }
        editorTurn(ConsortiumPrompts.reviseSystem(commentCount),
                ConsortiumPrompts.reviseTask(), "revise", thisRound, new LinkedHashSet<>(),
                (rawText, error) -> {
                    // A revision that is not one whole answer (see editedAnswer) is no revision: the
                    // answer it would have replaced stands. Reached only once every panelist that
                    // could have taken the revision over has failed it too.
                    String text = ConsortiumPrompts.editedAnswer(rawText);
                    if (rawText != null && text == null) {
                        recordError("revise", editor(), "reply was not one whole answer");
                    }
                    if (text == null) {
                        // The comments stand unanswered and there is no revised text to review.
                        // Returning the answer as it was, and saying so, beats another round over
                        // an unchanged answer. A wallet that ran dry on this very turn is reported
                        // as what it is rather than as an editor failure.
                        stoppedReason(insufficient() ? "insufficient_balance" : "revision_failed");
                        finishOk();
                        return;
                    }
                    synchronized (this) {
                        answer = text;
                        context.addAnswer("The answer as it now stands (revised after round "
                                + thisRound + ")", text);
                    }
                    beginReviewRound();
                });
    }

    private synchronized String editor() {
        return editor;
    }

    /**
     * A merge or a revision, by the editor — or, when the editor cannot do it, by the next
     * panelist that can.
     *
     * <p>Before failover, an editor that failed a merge left the call on the first draft, and one
     * that failed a revision ended it with {@code revision_failed} — a chapter's whole review
     * thrown away because one provider's account ran dry or one reply was malformed, while three
     * other models that had just read and judged the same text sat idle. The editor is only the
     * first of them by rank; any panelist that has answered on this call has read the record and
     * can write the answer. So a failed editor turn is handed on, in rank order, to panelists that
     * have answered this call and whose accounts can pay, each tried once; the one that succeeds
     * edits for the rest of the call. A wallet that cannot pay ends it as before — no other editor
     * changes that.
     */
    private void editorTurn(String system, String task, String stage, int round, Set<String> tried,
                            BiConsumer<String, String> onDone) {
        String current = editor();
        tried.add(current);
        if (ProviderCredit.get().isOutOfCredit(current)) {
            String next = nextEditor(tried, true);
            if (next != null) {
                changeEditor(current, next, ProviderCredit.get().failureFor(current) == null ? "out of credit"
                        : ProviderCredit.get().failureFor(current).detail(), stage, round);
                editorTurn(system, task, stage, round, tried, onDone);
                return;
            }
        }
        turn(current, system, task, stage, round, WHOLE_ANSWER, (text, error) -> {
            boolean usable = text != null && ConsortiumPrompts.editedAnswer(text) != null;
            if (!usable && !insufficient() && !isAborted()) {
                String next = nextEditor(tried, true);
                if (next != null) {
                    String reason = text == null ? error : "reply was not one whole answer";
                    if (text != null) {
                        recordError(stage, current, reason);
                    }
                    changeEditor(current, next, reason, stage, round);
                    editorTurn(system, task, stage, round, tried, onDone);
                    return;
                }
            }
            onDone.accept(text, error);
        });
    }

    /**
     * The next panelist in rank order that may take the editor's turn: not tried yet for it, its
     * account able to pay, and — unless {@code anyone} — one that has answered on this call, so
     * it is known to be reachable and has read the record.
     */
    private synchronized String nextEditor(Set<String> tried, boolean mustHaveAnswered) {
        for (String provider : panel) {
            if (tried.contains(provider) || ProviderCredit.get().isOutOfCredit(provider)) {
                continue;
            }
            if (mustHaveAnswered && !answeredBy.containsKey(provider)) {
                continue;
            }
            return provider;
        }
        return null;
    }

    private void changeEditor(String from, String to, String reason, String stage, int round) {
        synchronized (this) {
            editor = to;
            emit("editor_changed", "\"from\":" + Json.string(from) + ",\"to\":" + Json.string(to)
                    + ",\"reason\":" + Json.string(reason == null ? "failed" : reason)
                    + ",\"stage\":" + Json.string(stage) + ",\"round\":" + round);
            checkpoint();
        }
        LOG.info("consortium editor " + from + " -> " + to + " at " + stage + ": " + reason);
    }

    /**
     * One billed provider turn: hold a coin, call, settle what the call really cost, and hand back
     * the text (or the reason there is none). Identical accounting to a forwarded call — see
     * {@link UpstreamForwarder} — because a consortium turn is an ordinary paid call.
     *
     * <p>When the call carries a library, the turn may answer with {@code READ} lines instead; the
     * parts it asked for are added to the record and the same turn runs again — a new, equally
     * ordinary paid call — until it answers or reading closes. The stage that asked for the turn
     * sees only the final answer.
     *
     * @param task     what this turn is to do; rendered after the shared record, freshly on every
     *                 attempt, so a re-run after a read sees what it read
     * @param rejects  for an editor's turn: says why a reply is not usable (null when it is), so a
     *                 watching caller is told the turn failed rather than finished
     */
    private void turn(String provider, String system, String task, String stage, int round,
                      Function<String, String> rejects, BiConsumer<String, String> onDone) {
        int start;
        synchronized (this) {
            start = preferred.getOrDefault(provider, 0);
        }
        attempt(provider, system, task, stage, round, rejects, onDone, 0, null, start, null, null);
    }

    /**
     * One attempt at a turn.
     *
     * @param modelIndex   which of the provider's models this attempt uses
     * @param fellBackFrom the model the turn gave up on before this one, or null
     * @param fallbackWhy  why it gave up on it
     */
    private void attempt(String provider, String system, String task, String stage, int round,
                         Function<String, String> rejects, BiConsumer<String, String> onDone,
                         int readsThisTurn, String lastReadNote, int modelIndex, String fellBackFrom,
                         String fallbackWhy) {
        List<String> chain = models.get(provider);
        int index = Math.min(modelIndex, chain.size() - 1);
        String model = chain.get(index);
        String nextModel = index + 1 < chain.size() ? chain.get(index + 1) : null;
        String fallbackFields = fellBackFrom == null ? ""
                : ",\"fell_back_from\":" + Json.string(fellBackFrom) + ",\"fallback_reason\":" + Json.string(fallbackWhy);
        boolean readingOpen = false;
        String fullSystem = system;
        String fullTask = task;
        if (library != null) {
            int left = readsLeft();
            readingOpen = readsThisTurn < config.getConsortium().getMaxReadsPerTurn() && left > 0;
            fullSystem = system + ConsortiumPrompts.librarySystem();
            fullTask = task + ConsortiumPrompts.libraryTask(readingOpen, left, lastReadNote);
        }
        boolean mayRead = readingOpen;
        byte[] body = ChatAdapter.body(provider, model, fullSystem, context.forTurn(fullTask), maxOutputTokens);
        String path = ChatAdapter.path(provider, model);

        ledger.debitForCall(wallet, CALL_COST_AICOIN, provider, debit -> {
            if (!debit.isReachable()) {
                failTurn(stage, round, provider, model, "could not validate wallet");
                onDone.accept(null, "could not validate wallet");
                return;
            }
            if (!debit.isSuccess()) {
                synchronized (this) {
                    insufficient = true;
                }
                failTurn(stage, round, provider, model, "insufficient balance");
                onDone.accept(null, "insufficient balance");
                return;
            }
            synchronized (this) {
                calls++;
                pendingHolds.add(provider);
                emit("turn", turnFields(stage, round, provider, model, "started") + fallbackFields);
                checkpoint();
            }
            long startedNanos = System.nanoTime();
            long startedMillis = System.currentTimeMillis();
            UpstreamCall.post(group, config, healthTracker, provider, path, ChatAdapter.headers(provider), body,
                    timer, config.getConsortium().getTurnReadTimeoutSeconds(), result -> {
                        double seconds = Math.round((System.nanoTime() - startedNanos) / 1e8) / 10.0;
                        if (!result.isOk()) {
                            // Refused or never answered: the provider billed nothing, so neither
                            // does this — and if the provider has another model, the turn is not
                            // over yet. Unless the account cannot pay: then every model behind it
                            // refuses alike, and the error says so in the provider's words.
                            ledger.refund(wallet, CALL_COST_AICOIN, provider);
                            synchronized (this) {
                                pendingHolds.remove(provider);
                            }
                            String error = result.getError();
                            ProviderCredit.Failure credit = ProviderCredit.get().failureFor(provider);
                            boolean outOfCredit = credit != null && credit.lastSeenMillis >= startedMillis;
                            if (outOfCredit) {
                                error = "out of credit (HTTP " + credit.status + "): " + credit.message;
                            }
                            if (nextModel != null && !outOfCredit && !isAborted()) {
                                recordError(stage, provider, model + ": " + result.getError()
                                        + " (fell back to " + nextModel + ")");
                                attempt(provider, system, task, stage, round, rejects, onDone, readsThisTurn,
                                        lastReadNote, index + 1, model, result.getError());
                                return;
                            }
                            failTurn(stage, round, provider, model, error);
                            onDone.accept(null, error);
                            return;
                        }
                        String responseText = result.bodyText();
                        CostCalculator.Priced priced =
                                CostCalculator.price(provider, responseText, config.getModelPricing());
                        ledger.recordEvent(provider, priced.getCostUsd(),
                                priced.isTokensKnown() ? priced.getTokens() : -1, Instant.now(), wallet);
                        long charged = 1L;
                        if (config.isMeteredBilling()) {
                            charged = CoinMeter.coinsFor(priced.getCostUsd(), config.getCoinValueUsd());
                            ledger.settleCall(wallet, charged - CALL_COST_AICOIN, provider);
                        }
                        synchronized (this) {
                            pendingHolds.remove(provider);
                            coinsCharged += charged;
                            coinsByProvider.merge(provider, charged, Long::sum);
                        }

                        String text = ChatAdapter.text(provider, responseText);
                        if (text == null) {
                            // A 2xx with nothing in it — a model that spent its whole output cap
                            // thinking, or a shape this adapter doesn't read. Paid for, since the
                            // provider did the work and will bill for it; another model may still
                            // answer.
                            if (nextModel != null && !isAborted()) {
                                recordError(stage, provider, model + ": no text in response (fell back to "
                                        + nextModel + ")");
                                attempt(provider, system, task, stage, round, rejects, onDone, readsThisTurn,
                                        lastReadNote, index + 1, model, "no text in response");
                                return;
                            }
                            failTurn(stage, round, provider, model, "no text in response");
                            onDone.accept(null, "no text in response");
                            return;
                        }
                        synchronized (this) {
                            preferred.put(provider, index);
                            answeredBy.put(provider, model);
                        }
                        List<String> refs = library == null ? null : ConsortiumPrompts.readRequest(text);
                        if (refs != null) {
                            if (!mayRead) {
                                // Told reading was closed and asked anyway: there is no answer here,
                                // and another run would only be asked the same thing.
                                failTurn(stage, round, provider, model, "asked to read after reading was closed");
                                onDone.accept(null, "asked to read after reading was closed");
                                return;
                            }
                            if (isAborted()) {
                                failTurn(stage, round, provider, model, "client gone");
                                onDone.accept(null, "client gone");
                                return;
                            }
                            String note = read(provider, stage, round, refs);
                            checkpoint();
                            attempt(provider, system, task, stage, round, rejects, onDone,
                                    readsThisTurn + 1, note, index, fellBackFrom, fallbackWhy);
                            return;
                        }
                        String rejected = rejects == null ? null : rejects.apply(text);
                        if (rejected != null) {
                            emit("turn", turnFields(stage, round, provider, model, "failed")
                                    + ",\"seconds\":" + seconds + ",\"error\":" + Json.string(rejected) + fallbackFields);
                        } else {
                            StringBuilder finishedFields = new StringBuilder(turnFields(stage, round, provider, model, "finished"))
                                    .append(",\"seconds\":").append(seconds).append(fallbackFields);
                            if ("review".equals(stage)) {
                                boolean clean = ConsortiumPrompts.isClean(text);
                                finishedFields.append(",\"clean\":").append(clean);
                                if (!clean) {
                                    finishedFields.append(",\"comments\":").append(Json.string(
                                            text.length() <= COMMENT_PREVIEW_CHARS ? text
                                                    : text.substring(0, COMMENT_PREVIEW_CHARS) + "…"));
                                }
                            }
                            emit("turn", finishedFields.toString());
                        }
                        onDone.accept(text, null);
                    });
        });
    }

    /** Records a turn that produced nothing, and tells every watching client. */
    private void failTurn(String stage, int round, String provider, String model, String error) {
        recordError(stage, provider, error);
        emit("turn", turnFields(stage, round, provider, model, "failed") + ",\"error\":" + Json.string(error));
    }

    private synchronized int readsLeft() {
        return Math.max(0, config.getConsortium().getMaxLibraryReads() - libraryReads);
    }

    /**
     * Puts the parts a turn asked for into the shared record, and returns what to tell that turn
     * about its request when it runs again. A part already in the record is not added a second
     * time — it is the same text, and the whole panel can already see it — and the turn is told so,
     * along with which refs did not exist or were not written yet, so it does not spend its next
     * run asking for them again.
     */
    private String read(String provider, String stage, int round, List<String> refs) {
        List<String> titles = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        synchronized (this) {
            for (String ref : refs) {
                ConsortiumLibrary.Entry entry = library.get(ref);
                titles.add(entry == null ? "" : entry.title());
                if (entry == null) {
                    notes.add(ref + " is not in the contents.");
                    continue;
                }
                if (!entry.isWritten()) {
                    notes.add(ref + " is not written yet, so there is nothing to read.");
                    continue;
                }
                String already = readBy.get(ref);
                if (already != null) {
                    notes.add(ref + " is already in the record above (" + already + ").");
                    continue;
                }
                if (libraryReads >= config.getConsortium().getMaxLibraryReads()) {
                    notes.add(ref + " was not added: this call has read as many parts as it may.");
                    continue;
                }
                libraryReads++;
                String by = "read at " + ChatAdapter.displayName(provider) + "'s request " + stageLabel(stage, round);
                readBy.put(ref, by);
                context.addLibraryRead("Part " + ref
                        + (entry.title().isEmpty() ? "" : " “" + entry.title() + "”")
                        + " of the work — " + by, entry.text());
                added.add(ref);
            }
            StringBuilder json = new StringBuilder("{\"stage\":").append(Json.string(stage))
                    .append(",\"round\":").append(round)
                    .append(",\"provider\":").append(Json.string(provider))
                    .append(",\"refs\":").append(ConsortiumHandler.stringList(refs))
                    .append(",\"added\":").append(ConsortiumHandler.stringList(added));
            if (!notes.isEmpty()) {
                json.append(",\"notes\":").append(ConsortiumHandler.stringList(notes));
            }
            readJson.add(json.append("}").toString());
            StringBuilder fields = new StringBuilder("\"stage\":").append(Json.string(stage))
                    .append(",\"round\":").append(round)
                    .append(",\"provider\":").append(Json.string(provider))
                    .append(",\"refs\":").append(ConsortiumHandler.stringList(refs))
                    .append(",\"titles\":").append(ConsortiumHandler.stringList(titles))
                    .append(",\"added\":").append(ConsortiumHandler.stringList(added));
            if (!notes.isEmpty()) {
                fields.append(",\"notes\":").append(ConsortiumHandler.stringList(notes));
            }
            emit("read", fields.toString());
        }
        StringBuilder note = new StringBuilder("You asked to read ").append(String.join(", ", refs)).append(".");
        if (!added.isEmpty()) {
            note.append(" Added to the record above: ").append(String.join(", ", added)).append(".");
        }
        for (String line : notes) {
            note.append(' ').append(line);
        }
        return note.toString();
    }

    private static String stageLabel(String stage, int round) {
        switch (stage) {
            case "draft":
                return "while drafting";
            case "merge":
                return "while merging the drafts";
            case "review":
                return "in review round " + round;
            case "revise":
                return "while revising after round " + round;
            default:
                return "during " + stage;
        }
    }

    private synchronized boolean insufficient() {
        return insufficient;
    }

    private synchronized void stoppedReason(String reason) {
        stoppedReason = reason;
    }

    private synchronized void recordError(String stage, String provider, String error) {
        errorJson.add("{\"stage\":" + Json.string(stage) + ",\"provider\":" + Json.string(provider)
                + ",\"error\":" + Json.string(error) + "}");
    }

    // ---- Ending ------------------------------------------------------------------------------------

    private void sendInsufficient() {
        ledger.getBalance(wallet, balance -> {
            String json = "{\"error\":\"insufficient aicoin balance\",\"balance\":"
                    + (balance.isPresent() ? balance.get() : 0) + "}";
            complete(HttpResponseStatus.PAYMENT_REQUIRED, json);
        });
    }

    private void finish(HttpResponseStatus status, String error) {
        String json;
        synchronized (this) {
            json = "{\"error\":" + Json.string(error)
                    + ",\"calls\":" + calls + ",\"coins_charged\":" + coinsCharged
                    + ",\"errors\":[" + String.join(",", errorJson) + "]}";
        }
        complete(status, json);
    }

    private void finishOk() {
        StringBuilder json = new StringBuilder();
        synchronized (this) {
            json.append("{\"answer\":").append(Json.string(answer))
                    .append(",\"subject\":").append(Json.string(subject))
                    .append(",\"escalated\":").append(escalatedFrom != null)
                    .append(escalatedFrom == null ? "" : ",\"escalated_from\":" + Json.string(escalatedFrom))
                    .append(",\"settled\":").append("clean".equals(stoppedReason))
                    .append(",\"stopped_reason\":").append(Json.string(stoppedReason))
                    .append(",\"rounds\":").append(round)
                    .append(",\"panel\":").append(ConsortiumHandler.stringList(panel))
                    .append(",\"editor\":").append(Json.string(editor()))
                    .append(",\"mode\":").append(Json.string(modeName()))
                    .append(",\"quality\":").append(Json.string(best ? "best" : "standard"))
                    .append(",\"models\":").append(modelsJson())
                    .append(",\"max_output_tokens\":").append(maxOutputTokens)
                    .append(",\"calls\":").append(calls)
                    .append(",\"coins_charged\":").append(coinsCharged)
                    .append(",\"spend\":{");
            boolean firstSpend = true;
            for (Map.Entry<String, Long> entry : coinsByProvider.entrySet()) {
                json.append(firstSpend ? "" : ",").append(Json.string(entry.getKey())).append(":").append(entry.getValue());
                firstSpend = false;
            }
            json.append("}")
                    .append(",\"reviews\":[").append(String.join(",", reviewJson)).append("]")
                    .append(",\"errors\":[").append(String.join(",", errorJson)).append("]")
                    .append(",\"reads\":[").append(String.join(",", readJson)).append("]");
            if (!skippedJson.isEmpty()) {
                json.append(",\"skipped\":[").append(String.join(",", skippedJson)).append("]");
            }
            if (callId != null) {
                json.append(",\"call_id\":").append(Json.string(callId));
            }
            // A poll's answers are its whole result, so they are always returned; for the other
            // modes they are the working-out and come back only if asked for.
            if (includeTranscript || poll) {
                json.append(",\"answers\":[");
                for (int i = 0; i < drafts.size(); i++) {
                    json.append(i == 0 ? "" : ",")
                            .append("{\"provider\":").append(Json.string(draftProviders.get(i)))
                            .append(",\"model\":").append(Json.string(i < draftModels.size() && draftModels.get(i) != null
                                    ? draftModels.get(i) : modelFor(draftProviders.get(i))))
                            .append(",\"text\":").append(Json.string(drafts.get(i))).append("}");
                }
                json.append("]");
            }
            if (includeTranscript) {
                json.append(",\"drafts\":[");
                for (int i = 0; i < drafts.size(); i++) {
                    json.append(i == 0 ? "" : ",")
                            .append("{\"provider\":").append(Json.string(draftProviders.get(i)))
                            .append(",\"text\":").append(Json.string(drafts.get(i))).append("}");
                }
                json.append("]");
            }
            json.append("}");
        }
        complete(HttpResponseStatus.OK, json.toString());
    }

    /**
     * The outcome, kept and delivered. The last line is the body a plain call gets, as a
     * {@code result} event for a 200 or an {@code error} event carrying the status it would have
     * had; it is numbered like every other event, and stored with the final checkpoint so a client
     * coming back for a finished call is given exactly this, at no charge.
     */
    private void complete(HttpResponseStatus status, String json) {
        String pushTitle = null;
        String pushBody = null;
        synchronized (this) {
            if (finished) {
                return;
            }
            // Told by push only when nobody is watching: a reader with the stream open is looking
            // at the result as it lands, and a banner on top of it would be noise. A client that
            // comes back later for a call that already pushed is sent nothing more — the push
            // happens here, once, when the call ends.
            boolean watched = false;
            for (ConsortiumClient client : clients) {
                watched |= client.isOpen();
            }
            if (notify != null && !watched && PushDevices.get() != null) {
                if (status.code() == 200) {
                    pushTitle = str(notify.get("title"));
                    pushBody = str(notify.get("body"));
                } else if (notify.get("failure_title") instanceof String) {
                    pushTitle = str(notify.get("failure_title"));
                    pushBody = str(notify.get("failure_body"));
                }
            }
            if (pushTitle != null) {
                notified = true;
                json = json.substring(0, json.length() - 1) + ",\"notified\":true}";
            }
            seq++;
            String line = status.code() == 200
                    ? "{\"event\":\"result\",\"seq\":" + seq + "," + json.substring(1)
                    : "{\"event\":\"error\",\"seq\":" + seq + ",\"status\":" + status.code() + "," + json.substring(1);
            events.add(line);
            finished = true;
            phase = "done";
            finalStatus = status.code();
            finalBody = json;
            finalLine = line;
            if (store != null) {
                store.appendEvent(storeKey, line);
                checkpoint();
                store.finished(storeKey);
            }
            for (ConsortiumClient client : new ArrayList<>(clients)) {
                client.end(finalStatus, finalBody, seq, line, coinsCharged);
            }
            clients.clear();
        }
        LOG.log(Level.FINE, "consortium finished: {0} calls, {1} coins", new Object[] {calls, coinsCharged});
        if (pushTitle != null) {
            String payload = PushDevices.payload(pushTitle, pushBody,
                    notify.get("data") instanceof Map ? (Map<?, ?>) notify.get("data") : null, callId);
            PushDevices.get().push(wallet, str(notify.get("topic")), payload, results -> {
                synchronized (this) {
                    pushResults.clear();
                    pushResults.addAll(results);
                    checkpoint();
                }
                PushDevices.get().log(wallet, callId, results);
            });
        }
        onFinished.run();
    }

    /**
     * What to push when this call ends with nobody watching — see {@link #complete}. Only a
     * durable call can be told about later, so a call without an id never pushes.
     */
    synchronized void notifyOnFinish(Map<String, Object> notify) {
        this.notify = callId == null ? null : notify;
    }
}
