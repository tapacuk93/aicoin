package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code POST /consortium}, per CONTRACT.md's "Consortium" section: one request answered by every
 * configured AI at once, merged into a single answer, and then reviewed by all of them round after
 * round until no reviewer has a comment left.
 *
 * <p>The shape of a call is fixed:
 * <ol>
 *   <li><b>Draft</b> — every panelist answers the request independently, not seeing the others.</li>
 *   <li><b>Merge</b> — the editor turns those drafts into one answer.</li>
 *   <li><b>Review</b> — every panelist, including the editor, reviews that answer and either
 *       returns {@code NO COMMENTS} or lists what is wrong with it.</li>
 *   <li><b>Revise</b> — if anyone had comments, the editor applies the ones that are right, and
 *       the review round runs again on the result.</li>
 * </ol>
 * It ends when a whole round comes back clean, or when {@code consortium.maxRounds} rounds have
 * run — a cap that is not a detail: reviewers asked to find fault can always find some, so
 * "until no more comments" without a bound is a request that spends a wallet down to nothing.
 *
 * <p><b>Billing is not special.</b> Each turn is one ordinary paid call: one aicoin held before it,
 * the metered remainder settled from the provider's own reported usage afterwards, a refund if the
 * provider never answered. A consortium of four panelists over two rounds is thirteen calls and is
 * billed as thirteen calls. Nothing here bundles, discounts or hides that — the response says how
 * many calls it made and what they cost, and {@code X-Aicoin-Charged} carries the total, the same
 * header a single proxied call sets.
 *
 * <p>A wallet that runs out mid-call is not an error: the rounds stop where the coins did and the
 * best answer so far is returned, with {@code stopped_reason} saying why. Only a call that could
 * not afford its very first turn gets a {@code 402}.
 */
final class ConsortiumHandler {

    private static final Logger LOG = Logger.getLogger(ConsortiumHandler.class.getName());

    /** The hold placed before each turn — the same one coin every other paid call holds. */
    private static final double CALL_COST_AICOIN = 1.0;

    /** Longest request this will accept, so one call cannot pin megabytes per panelist. */
    private static final int MAX_PROMPT_CHARS = 32_000;

    private ConsortiumHandler() {
    }

    /**
     * The panel: the providers that can hold a chat turn ({@link ChatAdapter}), that this
     * deployment has a key and a model for, and that the caller asked for (all of them, if it
     * didn't). Order is {@link ChatAdapter#CHAT_PROVIDERS} order, so the panel — and the default
     * editor — are the same from one call to the next.
     */
    static List<String> panel(ProxyConfig config, Set<String> requested) {
        List<String> panel = new ArrayList<>();
        for (String provider : ChatAdapter.CHAT_PROVIDERS) {
            if (requested != null && !requested.contains(provider)) {
                continue;
            }
            ProviderConfig providerConfig = config.getProvider(provider);
            if (providerConfig == null || providerConfig.getApiKey() == null || providerConfig.getApiKey().isEmpty()) {
                continue;
            }
            String model = config.getConsortium().modelFor(provider);
            if (model == null || model.trim().isEmpty()) {
                continue;
            }
            panel.add(provider);
        }
        return panel;
    }

    /**
     * Who merges and revises: the caller's choice if it is on the panel, else the configured
     * editor if it is, else the first panelist. Never a provider that isn't on the panel — the
     * editor writes the answer, so it has to be one of the models that can be called.
     */
    static String editor(ProxyConfig config, List<String> panel, String requested) {
        if (requested != null && panel.contains(requested)) {
            return requested;
        }
        String configured = config.getConsortium().getEditor();
        if (configured != null && panel.contains(configured)) {
            return configured;
        }
        return panel.isEmpty() ? null : panel.get(0);
    }

    /**
     * @param requestBody the raw request body, read out of the inbound request <em>before</em> the
     *                    token check went asynchronous — by the time this runs Netty has recycled
     *                    the request object, so there is nothing left to read it from.
     */
    static void serve(ChannelHandlerContext ctx, byte[] requestBody, ProxyConfig config,
                       EventLoopGroup clientGroup, ProviderHealthTracker healthTracker,
                       AicoinLedger ledger, String walletAddress) {
        Map<?, ?> body = parseBody(requestBody);
        if (body == null) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be JSON");
            return;
        }
        Object promptObj = body.get("prompt");
        if (!(promptObj instanceof String) || ((String) promptObj).trim().isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be {\\\"prompt\\\":\\\"...\\\"}");
            return;
        }
        String prompt = (String) promptObj;
        if (prompt.length() > MAX_PROMPT_CHARS) {
            sendError(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "prompt must be at most " + MAX_PROMPT_CHARS + " characters");
            return;
        }

        Set<String> requested = null;
        Object providersObj = body.get("providers");
        if (providersObj instanceof List) {
            requested = new LinkedHashSet<>();
            for (Object p : (List<?>) providersObj) {
                if (p instanceof String) {
                    requested.add(((String) p).trim().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<String> panel = panel(config, requested);
        if (panel.isEmpty()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "no consortium providers are configured (need a key and a model for one of "
                            + String.join(", ", ChatAdapter.CHAT_PROVIDERS) + ")");
            return;
        }
        String editorRequested = body.get("editor") instanceof String
                ? ((String) body.get("editor")).trim().toLowerCase(Locale.ROOT) : null;
        String editor = editor(config, panel, editorRequested);

        int maxRounds = config.getConsortium().getMaxRounds();
        Object roundsObj = body.get("max_rounds");
        if (roundsObj instanceof Number) {
            int asked = ((Number) roundsObj).intValue();
            // The caller may ask for fewer rounds than the deployment allows, never more: the cap
            // is what bounds what one call can spend.
            maxRounds = Math.max(1, Math.min(maxRounds, asked));
        }
        boolean includeTranscript = Boolean.TRUE.equals(body.get("include_transcript"));

        new Session(ctx, config, clientGroup, healthTracker, ledger, walletAddress,
                prompt, panel, editor, maxRounds, includeTranscript).start();
    }

    private static Map<?, ?> parseBody(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            Object parsed = new Yaml().load(new String(bytes, CharsetUtil.UTF_8));
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One consortium call in flight. Every provider turn completes on some Netty event loop, so
     * all state transitions go through {@code synchronized} methods on this object; the round
     * counters in particular are what decide when a round is over, and losing one would hang the
     * request until the client gave up.
     */
    private static final class Session {
        private final ChannelHandlerContext ctx;
        private final ProxyConfig config;
        private final EventLoopGroup group;
        private final ProviderHealthTracker healthTracker;
        private final AicoinLedger ledger;
        private final String wallet;
        private final String prompt;
        private final List<String> panel;
        private final String editor;
        private final int maxRounds;
        private final boolean includeTranscript;

        private final List<String> draftProviders = new ArrayList<>();
        private final List<String> drafts = new ArrayList<>();
        private final List<String> reviewJson = new ArrayList<>();
        private final List<String> errorJson = new ArrayList<>();

        private String answer;
        private int round;
        private int calls;
        private long coinsCharged;
        private boolean insufficient;
        private boolean aborted;
        private boolean finished;
        private String stoppedReason = "clean";

        // Per-round collection, reset at the start of each round.
        private int outstanding;
        private final List<String> commentProviders = new ArrayList<>();
        private final List<String> comments = new ArrayList<>();

        private final ChannelFutureListener clientGone;

        Session(ChannelHandlerContext ctx, ProxyConfig config, EventLoopGroup group,
                 ProviderHealthTracker healthTracker, AicoinLedger ledger, String wallet, String prompt,
                 List<String> panel, String editor, int maxRounds, boolean includeTranscript) {
            this.ctx = ctx;
            this.config = config;
            this.group = group;
            this.healthTracker = healthTracker;
            this.ledger = ledger;
            this.wallet = wallet;
            this.prompt = prompt;
            this.panel = panel;
            this.editor = editor;
            this.maxRounds = maxRounds;
            this.includeTranscript = includeTranscript;
            // A consortium runs for minutes. A client that gave up in the middle is nobody to
            // spend the next round's coins for, so the rounds stop at the next boundary — the
            // turns already in flight are paid for and cannot be recalled.
            this.clientGone = f -> markAborted();
        }

        void start() {
            ctx.channel().closeFuture().addListener(clientGone);
            beginDrafts();
        }

        private synchronized void markAborted() {
            aborted = true;
        }

        private synchronized boolean isAborted() {
            return aborted;
        }

        private void beginDrafts() {
            synchronized (this) {
                outstanding = panel.size();
            }
            String system = ConsortiumPrompts.draftSystem();
            for (String provider : panel) {
                turn(provider, system, prompt, "draft", (text, error) -> {
                    synchronized (this) {
                        if (text != null) {
                            draftProviders.add(provider);
                            drafts.add(text);
                        }
                        if (--outstanding > 0) {
                            return;
                        }
                    }
                    afterDrafts();
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
                // Nobody answered: either every provider failed, or the wallet could not pay for
                // the first turn. The wallet case is the one the client can act on.
                if (insufficient()) {
                    sendInsufficient();
                } else {
                    finish(HttpResponseStatus.BAD_GATEWAY, "no panelist answered");
                }
                return;
            }
            if (draftsSnapshot.size() == 1) {
                // One draft is already the merged answer; a merge turn here would only cost a call
                // to rewrite a single input.
                setAnswer(draftsSnapshot.get(0));
                beginReviewRound();
                return;
            }
            turn(editor, ConsortiumPrompts.mergeSystem(draftsSnapshot.size()),
                    ConsortiumPrompts.mergeUser(prompt, providersSnapshot, draftsSnapshot), "merge",
                    (text, error) -> {
                        // An editor that failed to merge must not sink the call: the first draft is
                        // a real answer to the request, and the review rounds still run over it.
                        setAnswer(text != null ? text : draftsSnapshot.get(0));
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
                outstanding = panel.size();
                commentProviders.clear();
                comments.clear();
            }
            String system = ConsortiumPrompts.reviewSystem();
            String user = ConsortiumPrompts.reviewUser(prompt, answer());
            int thisRound = round;
            for (String provider : panel) {
                turn(provider, system, user, "review", (text, error) -> {
                    boolean clean = text != null && ConsortiumPrompts.isClean(text);
                    synchronized (this) {
                        if (text != null) {
                            reviewJson.add("{\"round\":" + thisRound
                                    + ",\"provider\":" + Json.string(provider)
                                    + ",\"clean\":" + clean
                                    + ",\"comments\":" + (clean ? "null" : Json.string(text)) + "}");
                            if (!clean) {
                                commentProviders.add(provider);
                                comments.add(text);
                            }
                        }
                        if (--outstanding > 0) {
                            return;
                        }
                    }
                    afterReviewRound();
                });
            }
        }

        private void afterReviewRound() {
            List<String> providersSnapshot;
            List<String> commentsSnapshot;
            synchronized (this) {
                providersSnapshot = new ArrayList<>(commentProviders);
                commentsSnapshot = new ArrayList<>(comments);
            }
            if (commentsSnapshot.isEmpty()) {
                // A whole round with nothing to say: this is what the call was asking for.
                stoppedReason("clean");
                finishOk();
                return;
            }
            if (round >= maxRounds) {
                stoppedReason("round_limit");
                finishOk();
                return;
            }
            if (isAborted() || insufficient()) {
                stoppedReason(isAborted() ? "client_gone" : "insufficient_balance");
                finishOk();
                return;
            }
            turn(editor, ConsortiumPrompts.reviseSystem(commentsSnapshot.size()),
                    ConsortiumPrompts.reviseUser(prompt, answer(), providersSnapshot, commentsSnapshot), "revise",
                    (text, error) -> {
                        if (text == null) {
                            // The comments stand unanswered and there is no revised text to review.
                            // Returning the answer as it was, and saying so, beats another round
                            // over an unchanged answer. A wallet that ran dry on this very turn is
                            // reported as what it is rather than as an editor failure.
                            stoppedReason(insufficient() ? "insufficient_balance" : "revision_failed");
                            finishOk();
                            return;
                        }
                        setAnswer(text);
                        beginReviewRound();
                    });
        }

        /**
         * One billed provider turn: hold a coin, call, settle what the call really cost, and hand
         * back the text (or the reason there is none). Identical accounting to a forwarded call —
         * see {@link UpstreamForwarder} — because a consortium turn is an ordinary paid call.
         */
        private void turn(String provider, String system, String user, String stage,
                           BiConsumer<String, String> onDone) {
            String model = config.getConsortium().modelFor(provider);
            byte[] body = ChatAdapter.body(provider, model, system, user, config.getConsortium().getMaxOutputTokens());
            String path = ChatAdapter.path(provider, model);

            ledger.debitForCall(wallet, CALL_COST_AICOIN, provider, debit -> {
                if (!debit.isReachable()) {
                    recordError(stage, provider, "could not validate wallet");
                    onDone.accept(null, "could not validate wallet");
                    return;
                }
                if (!debit.isSuccess()) {
                    markInsufficient();
                    recordError(stage, provider, "insufficient balance");
                    onDone.accept(null, "insufficient balance");
                    return;
                }
                countCall();
                UpstreamCall.post(group, config, healthTracker, provider, path, ChatAdapter.headers(provider), body,
                        result -> {
                            if (!result.isOk()) {
                                ledger.refund(wallet, CALL_COST_AICOIN, provider);
                                recordError(stage, provider, result.getError());
                                onDone.accept(null, result.getError());
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
                            addCharged(charged);

                            String text = ChatAdapter.text(provider, responseText);
                            if (text == null) {
                                // A 2xx with nothing in it — a model that spent its whole output
                                // cap thinking, or a shape this adapter doesn't read. Paid for,
                                // since the provider did the work and will bill for it.
                                recordError(stage, provider, "no text in response");
                            }
                            onDone.accept(text, text == null ? "no text in response" : null);
                        });
            });
        }

        private synchronized void countCall() {
            calls++;
        }

        private synchronized void addCharged(long charged) {
            coinsCharged += charged;
        }

        private synchronized void markInsufficient() {
            insufficient = true;
        }

        private synchronized boolean insufficient() {
            return insufficient;
        }

        private synchronized void setAnswer(String text) {
            answer = text;
        }

        private synchronized String answer() {
            return answer;
        }

        private synchronized void stoppedReason(String reason) {
            stoppedReason = reason;
        }

        private synchronized void recordError(String stage, String provider, String error) {
            errorJson.add("{\"stage\":" + Json.string(stage) + ",\"provider\":" + Json.string(provider)
                    + ",\"error\":" + Json.string(error) + "}");
        }

        private void sendInsufficient() {
            ledger.getBalance(wallet, balance -> {
                String json = "{\"error\":\"insufficient aicoin balance\",\"balance\":"
                        + (balance.isPresent() ? balance.get() : 0) + "}";
                send(HttpResponseStatus.PAYMENT_REQUIRED, json);
            });
        }

        private void finish(HttpResponseStatus status, String error) {
            send(status, "{\"error\":" + Json.string(error)
                    + ",\"calls\":" + calls + ",\"coins_charged\":" + coinsCharged
                    + ",\"errors\":[" + String.join(",", errorJson) + "]}");
        }

        private void finishOk() {
            StringBuilder json = new StringBuilder();
            synchronized (this) {
                json.append("{\"answer\":").append(Json.string(answer))
                        .append(",\"settled\":").append("clean".equals(stoppedReason))
                        .append(",\"stopped_reason\":").append(Json.string(stoppedReason))
                        .append(",\"rounds\":").append(round)
                        .append(",\"panel\":[");
                for (int i = 0; i < panel.size(); i++) {
                    json.append(i == 0 ? "" : ",").append(Json.string(panel.get(i)));
                }
                json.append("],\"editor\":").append(Json.string(editor))
                        .append(",\"calls\":").append(calls)
                        .append(",\"coins_charged\":").append(coinsCharged)
                        .append(",\"reviews\":[").append(String.join(",", reviewJson)).append("]")
                        .append(",\"errors\":[").append(String.join(",", errorJson)).append("]");
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
            send(HttpResponseStatus.OK, json.toString());
        }

        private void send(HttpResponseStatus status, String json) {
            synchronized (this) {
                if (finished) {
                    return;
                }
                finished = true;
            }
            ctx.channel().closeFuture().removeListener(clientGone);
            byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            // The same header a single proxied call sets, for the same reason: a client can show
            // what the call cost without being told how the proxy bills.
            response.headers().set("X-Aicoin-Charged", Long.toString(coinsCharged));
            HttpUtil.setContentLength(response, bytes.length);
            ctx.writeAndFlush(response);
            LOG.log(Level.FINE, "consortium finished: {0} calls, {1} coins", new Object[] {calls, coinsCharged});
        }
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
