package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    /** Longest request accepted, matching the text endpoint's cap. */
    private static final int MAX_PROMPT_CHARS = 400_000;

    static String stringList(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            json.append(i == 0 ? "" : ",").append(Json.string(values.get(i)));
        }
        return json.append("]").toString();
    }

    private ConsortiumHandler() {
    }

    /** The `skip` field as lower-case provider names; anything that is not a list of strings is no skip. */
    static Set<String> skipped(Object field) {
        Set<String> skip = new LinkedHashSet<>();
        if (field instanceof List) {
            for (Object p : (List<?>) field) {
                if (p instanceof String) skip.add(((String) p).trim().toLowerCase(Locale.ROOT));
            }
        }
        return skip;
    }

    /** {@code panel} in its order, less {@code skip}. */
    static List<String> withoutSkipped(List<String> panel, Set<String> skip) {
        List<String> kept = new ArrayList<>(panel);
        kept.removeAll(skip);
        return kept;
    }

    /**
     * The panel: the providers that can hold a chat turn ({@link ChatAdapter}), that this
     * deployment has a key and a model for, and that the caller asked for (all of them, if it
     * didn't). Order is {@link ChatAdapter#CHAT_PROVIDERS} order, so the panel — and the default
     * editor — are the same from one call to the next.
     */
    static List<String> panel(ProxyConfig config, Set<String> requested) {
        return panel(config, requested, null, null);
    }

    /**
     * The panel, ordered by what each member is rated at for this request's subject.
     *
     * <p>Order is not cosmetic here: the first panelist is the default editor, which is the model
     * that merges every draft and writes every revision, and in lead mode it is the model that
     * writes the answer at all. Ranking by {@link ProviderSkills} for the tagged subject is what
     * puts the strongest model for the question in that seat rather than whichever provider
     * happens to come first in the configuration.
     *
     * <p>Nobody is dropped for a low rating. A panel is worth having because its members disagree,
     * and a weaker model that spots something the editor missed has earned its turn — the rating
     * decides who writes, not who is allowed to speak.
     *
     * @param subject  the tagged subject, or null to keep the configured order
     * @param liveness used to skip a provider last seen down; null to skip that check
     */
    static List<String> panel(ProxyConfig config, Set<String> requested, String subject,
                               ProviderLiveness liveness) {
        return panel(config, requested, subject, liveness, false);
    }

    /**
     * As above, seating each provider with its {@code consortium.bestModels} model when
     * {@code best} — see {@link ConsortiumConfig#modelFor(String, boolean)}.
     */
    static List<String> panel(ProxyConfig config, Set<String> requested, String subject,
                               ProviderLiveness liveness, boolean best) {
        List<String> panel = new ArrayList<>();
        for (String provider : ChatAdapter.CHAT_PROVIDERS) {
            if (requested != null && !requested.contains(provider)) {
                continue;
            }
            ProviderConfig providerConfig = config.getProvider(provider);
            if (providerConfig == null || providerConfig.getApiKey() == null || providerConfig.getApiKey().isEmpty()) {
                continue;
            }
            String model = config.getConsortium().modelFor(provider, best);
            if (model == null || model.trim().isEmpty()) {
                continue;
            }
            if (liveness != null
                    && ProviderLiveness.STATE_DOWN.equals(liveness.probeFor(provider).getState())) {
                continue;
            }
            // An account that cannot pay is never seated, liveness or not: every turn it was
            // given would be refused, and as the first-ranked panelist it would be the editor.
            // Learned from real calls (ProviderCredit), and cleared by the next one that succeeds.
            if (ProviderCredit.get().isOutOfCredit(provider)) {
                continue;
            }
            panel.add(provider);
        }
        if (subject != null) {
            panel.sort(config.getCapabilities().getSkills()
                    .ranking(Capability.TEXT, subject, ChatAdapter.CHAT_PROVIDERS));
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
    /**
     * Whether this call is led by one model ({@code lead}) or drafted by the whole panel
     * ({@code panel}). {@code auto} decides on the size of the context that came with the request.
     *
     * @param mode           the caller's choice: {@code auto}, {@code lead} or {@code panel}
     * @param background     the context the caller supplied, or null
     * @param leadFromChars  the size at or above which {@code auto} means lead
     */
    static boolean isLeadMode(String mode, String background, int leadFromChars) {
        if (mode.equals("lead")) {
            return true;
        }
        if (!mode.equals("auto")) {
            return false;
        }
        return background != null && background.length() >= leadFromChars;
    }

    static void serve(ChannelHandlerContext ctx, byte[] requestBody, ProxyConfig config,
                       EventLoopGroup clientGroup, ProviderHealthTracker healthTracker,
                       AicoinLedger ledger, String walletAddress) {
        Map<?, ?> body = parseBody(requestBody);
        if (body == null) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be JSON");
            return;
        }
        // A call with an id is durable — see "Durable calls" in CONTRACT.md. If the id already
        // names one of this wallet's calls, this request is a client coming back for it: it is
        // attached, replayed or resumed, and the rest of this body is not read at all. A call is
        // what its id was first given to; a second body under the same id cannot change it.
        Object callIdObj = body.get("call_id");
        String callId = null;
        if (callIdObj != null) {
            if (!(callIdObj instanceof String) || !CALL_ID.matcher((String) callIdObj).matches()) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                        "call_id must be 1-128 characters of letters, digits, '.', '_', ':' or '-'");
                return;
            }
            callId = (String) callIdObj;
            ConsortiumCalls calls = ConsortiumCalls.get();
            if (calls != null) {
                String id = callId;
                calls.openOrCreate(ctx, Boolean.TRUE.equals(body.get("stream")),
                        ConsortiumStore.key(walletAddress, callId),
                        () -> serveNew(ctx, requestBody, body, id, config, clientGroup, healthTracker, ledger,
                                walletAddress));
                return;
            }
        }
        serveNew(ctx, requestBody, body, callId, config, clientGroup, healthTracker, ledger, walletAddress);
    }

    private static final java.util.regex.Pattern CALL_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private static void serveNew(ChannelHandlerContext ctx, byte[] requestBody, Map<?, ?> body, String callId,
                                 ProxyConfig config, EventLoopGroup clientGroup,
                                 ProviderHealthTracker healthTracker, AicoinLedger ledger, String walletAddress) {
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

        // The subject the panel is ranked on. A caller who names one is taken at their word.
        String subject = body.get("subject") instanceof String
                && SubjectTagger.isKnown((String) body.get("subject"))
                ? ((String) body.get("subject")).trim().toLowerCase(Locale.ROOT)
                : SubjectTagger.tag(prompt, body.get("context") instanceof String
                        ? (String) body.get("context") : null);
        // `quality: "best"` seats each provider's strongest confirmed model instead of its
        // everyday one. A choice of the caller's, not a ranking of ours: the best models cost
        // several times as much per turn, and only the caller knows whether this request is
        // worth that — a book chapter someone will read is; a quick check is not.
        String quality = body.get("quality") instanceof String
                ? ((String) body.get("quality")).trim().toLowerCase(Locale.ROOT) : "standard";
        if (!quality.equals("standard") && !quality.equals("best")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "quality must be standard or best");
            return;
        }
        boolean best = quality.equals("best");
        // `skip` is the other way of narrowing the panel: by who to leave out rather than who to
        // seat. It is what a reader's setting wants ("not Kimi, it takes ten minutes a turn"), and
        // unlike `providers` it keeps seating a provider added to the panel later.
        Set<String> skip = skipped(body.get("skip"));
        List<String> seated = panel(config, requested, subject, null, best);
        List<String> panel = withoutSkipped(seated, skip);
        if (!seated.isEmpty() && panel.isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                    "skip leaves no one on the panel (skipped: " + String.join(", ", skip) + ")");
            return;
        }
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
        String mode = body.get("mode") instanceof String
                ? ((String) body.get("mode")).trim().toLowerCase(Locale.ROOT) : "auto";
        if (!mode.equals("auto") && !mode.equals("lead") && !mode.equals("panel")
                && !mode.equals("poll")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                    "mode must be auto, lead, panel or poll");
            return;
        }
        // Background every panelist sees, on every turn, alongside the request: the caller's own
        // context for the question. Part of the shared record rather than glued onto the prompt,
        // so the editor and the reviewers weigh it too, not only the drafters.
        String background = body.get("context") instanceof String ? (String) body.get("context") : null;
        if (background != null && background.length() > MAX_PROMPT_CHARS) {
            sendError(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "context must be at most " + MAX_PROMPT_CHARS + " characters");
            return;
        }

        // Which way to spend the call. Drafting in parallel is worth it when the request is the
        // whole input: several models answering the same short question independently disagree in
        // useful ways, and the merge is where that pays off. Once a large context comes with the
        // request — a directory, a document, a session's history — that stops being true: every
        // draft is then mostly a re-reading of the same material, billed once per panelist, and
        // the drafts converge anyway because the context, not the model, is doing the work. So a
        // context-heavy call is led by one model, and the others improve its answer round by round
        // instead of each writing their own from scratch.
        // A poll is the panel without the merge or the rounds: everybody answers once,
        // independently, and every answer is returned as it was given. It is here because
        // merging is not always what a caller wants. Some questions are decisions - should this
        // ship, is this correct - and for those the disagreement is the product: that three
        // models said yes and one said no is a different fact from a paragraph that reads as
        // though the panel agreed. Folding independent judgements into one answer destroys
        // exactly the information such a caller came for.
        // A library never makes a call `lead`: only `context` is counted. The library's text is not
        // in the record at all — only its index is — so its size says nothing about how much each
        // drafter would be re-reading, which is the whole reason `auto` leads a heavy context.
        boolean poll = mode.equals("poll");
        boolean lead = !poll
                && isLeadMode(mode, background, config.getConsortium().getLeadContextChars());

        // Per-turn output cap. The default suits a short answer; a chapter-length one, written by
        // a model that thinks before it writes, runs out mid-answer under it and comes back as an
        // incomplete text that is paid for and then thrown away. So a caller that knows its answer
        // is long may raise it — up to the configured ceiling, which is what bounds a turn's cost.
        Integer requestedTokens = null;
        Object tokensObj = body.get("max_output_tokens");
        if (tokensObj != null) {
            if (!(tokensObj instanceof Number) || ((Number) tokensObj).longValue() < 1) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST, "max_output_tokens must be a positive integer");
                return;
            }
            requestedTokens = (int) Math.min(Integer.MAX_VALUE, ((Number) tokensObj).longValue());
        }
        int maxOutputTokens = config.getConsortium().outputTokensFor(requestedTokens);

        ConsortiumLibrary library;
        try {
            library = ConsortiumLibrary.parse(body.get("library"),
                    config.getConsortium().getMaxLibraryChars(), config.getConsortium().getMaxLibraryEntries());
        } catch (ConsortiumLibrary.Invalid e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, e.getMessage());
            return;
        }

        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        List<String> skipped = new ArrayList<>();
        for (String provider : ChatAdapter.CHAT_PROVIDERS) {
            ProviderCredit.Failure credit = ProviderCredit.get().failureFor(provider);
            if (credit != null && (requested == null || requested.contains(provider))
                    && config.getConsortium().modelFor(provider, best) != null
                    && config.getProvider(provider) != null && config.getProvider(provider).getApiKey() != null
                    && !config.getProvider(provider).getApiKey().isEmpty()) {
                skipped.add(provider);
            }
        }
        Map<String, List<String>> models = new java.util.LinkedHashMap<>();
        for (String provider : panel) {
            models.put(provider, config.getConsortium().modelsFor(provider, best));
        }
        ConsortiumCalls calls = callId == null ? null : ConsortiumCalls.get();
        ConsortiumSession session = new ConsortiumSession(config, clientGroup, healthTracker, ledger, walletAddress,
                calls == null ? null : callId, calls == null ? null : calls.store(), prompt, background, panel, editor,
                models, maxRounds, includeTranscript, lead, poll, subject, null, best, maxOutputTokens, library,
                UpstreamTimer.of(ctx.channel()), 0, 0, Map.of());
        // Told by push when it ends with nobody watching — durable calls only, since only those run
        // on without a client; a `notify` on a call without an id is ignored.
        if (callId != null && body.get("notify") != null) {
            Object notify = parseNotify(body.get("notify"));
            if (notify instanceof String) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST, (String) notify);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) notify;
            session.notifyOnFinish(parsed);
        }
        for (String provider : skipped) {
            session.skipped(provider, ProviderCredit.get().failureFor(provider) == null ? "out of credit"
                    : ProviderCredit.get().failureFor(provider).detail());
        }
        Runnable begin = () -> {
            if (calls != null) {
                calls.create(session, new String(requestBody, CharsetUtil.UTF_8), ctx, stream);
            } else {
                session.start(ctx, stream);
            }
        };
        if (!stream) {
            begin.run();
            return;
        }
        // A streamed call commits to 200 the moment it starts, so the one refusal a caller acts
        // on — an empty wallet, which opens the buy sheet — has to be decided before that, or it
        // would arrive as an event inside a 200 instead of the 402 every client already handles.
        // The first turn's own debit still decides in the end; this only keeps the ordinary case
        // ordinary. A balance that cannot be read is left to that debit to report.
        ledger.getBalance(walletAddress, balance -> {
            if (balance.isPresent() && balance.get() < CALL_COST_AICOIN) {
                byte[] bytes = ("{\"error\":\"insufficient aicoin balance\",\"balance\":" + balance.get() + "}")
                        .getBytes(CharsetUtil.UTF_8);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.PAYMENT_REQUIRED, Unpooled.wrappedBuffer(bytes));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                HttpUtil.setContentLength(response, bytes.length);
                ctx.writeAndFlush(response);
                return;
            }
            begin.run();
        });
    }

    /**
     * Runs a consortium for a request that arrived somewhere else — {@code POST /text}, where the
     * model that read it asked for the panel rather than answering alone.
     *
     * <p>The call that did the asking is carried in rather than forgotten: it was a real paid call,
     * and a response that reported one fewer call than the wallet was charged for would be a
     * receipt that does not add up.
     *
     * @param from        the endpoint that escalated, named in the response
     * @param priorCalls  calls already made and charged for this request
     * @param priorCoins  coins already charged for it
     * @param priorSpend  those coins, by provider
     */
    static void escalate(ChannelHandlerContext ctx, ProxyConfig config, EventLoopGroup clientGroup,
                          ProviderHealthTracker healthTracker, AicoinLedger ledger, String wallet,
                          String prompt, String background, List<String> panel, String editor,
                          String subject, String from, int priorCalls, long priorCoins,
                          Map<String, Long> priorSpend) {
        Map<String, List<String>> models = new java.util.LinkedHashMap<>();
        for (String provider : panel) {
            models.put(provider, config.getConsortium().modelsFor(provider, false));
        }
        new ConsortiumSession(config, clientGroup, healthTracker, ledger, wallet, null, null, prompt, background,
                panel, editor, models, config.getConsortium().getMaxRounds(), false, false, false, subject, from,
                false, config.getConsortium().getMaxOutputTokens(), null, UpstreamTimer.of(ctx.channel()),
                priorCalls, priorCoins, priorSpend).start(ctx, false);
    }

    private static Map<?, ?> parseBody(byte[] bytes) {
        if (bytes.length == 0) {
            return null;
        }
        try {
            Object parsed = Json.load(new String(bytes, CharsetUtil.UTF_8));
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Largest {@code notify.data}, as JSON: APNs takes 4 KB in all, and the alert needs room too. */
    static final int MAX_NOTIFY_DATA_CHARS = 1024;

    /**
     * A request's {@code notify}, checked: a map of exactly the fields a push needs, or the reason
     * it is not one. {@code topic} is the app's bundle id; {@code title} is required, the rest
     * optional; {@code data} is the caller's, opaque, and goes into the push beside {@code aps}.
     */
    static Object parseNotify(Object raw) {
        if (!(raw instanceof Map)) {
            return "notify must be an object";
        }
        Map<?, ?> notify = (Map<?, ?>) raw;
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        Object topic = notify.get("topic");
        if (!(topic instanceof String) || !((String) topic).matches("[A-Za-z0-9][A-Za-z0-9.\\-]{0,199}")) {
            return "notify.topic must be the app's bundle id";
        }
        out.put("topic", topic);
        for (String field : List.of("title", "body", "failure_title", "failure_body")) {
            Object value = notify.get(field);
            if (value == null) {
                continue;
            }
            if (!(value instanceof String) || ((String) value).length() > 500) {
                return "notify." + field + " must be text of at most 500 characters";
            }
            out.put(field, value);
        }
        if (!(out.get("title") instanceof String) || ((String) out.get("title")).isBlank()) {
            return "notify.title is required";
        }
        Object data = notify.get("data");
        if (data != null) {
            if (!(data instanceof Map) || Json.write(data).length() > MAX_NOTIFY_DATA_CHARS) {
                return "notify.data must be an object of at most " + MAX_NOTIFY_DATA_CHARS + " characters as JSON";
            }
            out.put("data", data);
        }
        return out;
    }

    static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
