package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * The consolidated endpoints: {@code POST /text}, {@code POST /image}, {@code POST /audio}.
 *
 * <p>One shape in — a prompt — and one shape out, whichever provider ends up answering. The caller
 * does not name a provider, does not write a provider's request body, and does not read a
 * provider's response shape. What it gets instead is a routing decision it can audit: the answer,
 * the provider that produced it, the subject the request was tagged as, and the ranked list that
 * subject produced.
 *
 * <p>How the provider is chosen:
 * <ol>
 *   <li>{@link SubjectTagger} tags the request — {@code code}, {@code legal}, {@code translation}
 *       — from the prompt and the caller's context.</li>
 *   <li>{@link ProviderSkills} ranks the providers rated for that capability and subject, best
 *       first, skipping any this deployment has no key for, no model for, or that {@link
 *       ProviderLiveness} last found <em>down</em>. A rating is an opinion; a dead provider is a
 *       fact, and the fact wins.</li>
 *   <li>The best one answers. If it fails outright the next takes it, up to {@link #MAX_ATTEMPTS}
 *       — a failed call is refunded, so a failover costs the caller nothing but the wait.</li>
 * </ol>
 *
 * <p><b>Escalation.</b> A model answering {@code POST /text} alone may reply with {@link
 * ConsortiumPrompts#ESCALATE} instead of an answer, and the same request is then put to the whole
 * panel — drafted, merged, reviewed to a clean round. The judgement of whether one model is enough
 * belongs to a model that has read the request, not to a rule about its length; but it spends the
 * caller's coins several times over, so the model is told the price, the phrase counts only as an
 * entire reply, and a deployment can turn it off with {@code capabilities.text.escalation}.
 */
final class CapabilityHandler {

    /** Longest request accepted, matching the consortium's cap — the same prompt may end up there. */
    private static final int MAX_PROMPT_CHARS = 32_000;

    /**
     * How many providers one request may be tried against. A failing provider is refunded, so this
     * is not a cost limit — it is a latency limit, since each attempt is a full upstream timeout in
     * the worst case, and a caller waiting on eight of those has been failed already.
     */
    private static final int MAX_ATTEMPTS = 3;

    /** What a speech request may be asked to read out. Longer is a document, not a clip. */
    private static final int MAX_SPEECH_CHARS = 5_000;

    private CapabilityHandler() {
    }

    static void serve(ChannelHandlerContext ctx, Capability capability, byte[] requestBody,
                       ProxyConfig config, EventLoopGroup group, ProviderHealthTracker healthTracker,
                       ProviderLiveness liveness, AicoinLedger ledger, String wallet) {
        Map<?, ?> body = parseBody(requestBody);
        if (body == null) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be JSON");
            return;
        }
        Object promptObj = body.get("prompt");
        if (!(promptObj instanceof String) || ((String) promptObj).trim().isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be {\"prompt\":\"...\"}");
            return;
        }
        String prompt = (String) promptObj;
        int limit = capability == Capability.AUDIO ? MAX_SPEECH_CHARS : MAX_PROMPT_CHARS;
        if (prompt.length() > limit) {
            sendError(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "prompt must be at most " + limit + " characters");
            return;
        }
        String background = body.get("context") instanceof String ? (String) body.get("context") : null;
        if (background != null && background.length() > MAX_PROMPT_CHARS) {
            sendError(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
                    "context must be at most " + MAX_PROMPT_CHARS + " characters");
            return;
        }

        // A caller who names the subject is taken at their word: they know what they are asking
        // about, and the tagger is only a guess at it.
        String subject;
        if (body.get("subject") instanceof String) {
            subject = ((String) body.get("subject")).trim().toLowerCase(Locale.ROOT);
            if (!SubjectTagger.isKnown(subject)) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                        "subject must be one of " + String.join(", ", SubjectTagger.subjects()));
                return;
            }
        } else {
            subject = SubjectTagger.tag(prompt, background);
        }

        List<String> ranked = rank(config, liveness, capability, subject);
        String pinned = body.get("provider") instanceof String
                ? ((String) body.get("provider")).trim().toLowerCase(Locale.ROOT) : null;
        if (pinned != null) {
            if (!ranked.contains(pinned)) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST, "provider " + pinned
                        + " cannot serve " + capability.path() + " here (candidates: "
                        + (ranked.isEmpty() ? "none" : String.join(", ", ranked)) + ")");
                return;
            }
            // Named, so it is the only one tried: a caller who chose a provider did not ask to be
            // quietly moved to a different one.
            ranked = List.of(pinned);
        }
        if (ranked.isEmpty()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "no provider is configured and reachable for " + capability.path());
            return;
        }

        boolean escalate = capability == Capability.TEXT
                && config.getCapabilities().isEscalationEnabled()
                && !Boolean.FALSE.equals(body.get("escalate"));
        String voice = body.get("voice") instanceof String ? (String) body.get("voice") : null;
        String size = body.get("size") instanceof String ? (String) body.get("size") : "1024x1024";

        new Attempt(ctx, config, group, healthTracker, ledger, wallet, capability, prompt, background,
                subject, ranked, escalate, voice, size).next();
    }

    /**
     * The providers that could serve this capability, best first: rated for it, configured with a
     * key and a model, known to this proxy's adapters, and not last seen down.
     */
    static List<String> rank(ProxyConfig config, ProviderLiveness liveness, Capability capability,
                              String subject) {
        return config.getCapabilities().getSkills().rank(capability, subject,
                provider -> isAvailable(config, liveness, capability, provider));
    }

    private static boolean isAvailable(ProxyConfig config, ProviderLiveness liveness, Capability capability,
                                        String provider) {
        ProviderConfig providerConfig = config.getProvider(provider);
        if (providerConfig == null || providerConfig.getApiKey() == null || providerConfig.getApiKey().isEmpty()) {
            return false;
        }
        if (!MediaAdapter.supports(capability, provider)) {
            return false;
        }
        if (modelFor(config, capability, provider).isEmpty()) {
            return false;
        }
        // Down is the only state that disqualifies. `unknown` means the proxy restarted a moment
        // ago and has not asked yet, which is no reason to refuse to route to a provider that has
        // been fine all week.
        return liveness == null
                || !ProviderLiveness.STATE_DOWN.equals(liveness.probeFor(provider).getState());
    }

    /** Text answers with the model the consortium would use; media models come from their own config. */
    private static String modelFor(ProxyConfig config, Capability capability, String provider) {
        if (capability == Capability.TEXT) {
            String model = config.getConsortium().modelFor(provider);
            return model == null ? "" : model.trim();
        }
        return config.getCapabilities().modelFor(capability, provider);
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

    /** One request working down the ranked list until something answers. */
    private static final class Attempt {
        private final ChannelHandlerContext ctx;
        private final ProxyConfig config;
        private final EventLoopGroup group;
        private final ProviderHealthTracker healthTracker;
        private final AicoinLedger ledger;
        private final String wallet;
        private final Capability capability;
        private final String prompt;
        private final String background;
        private final String subject;
        private final List<String> ranked;
        private final boolean escalate;
        private final String voice;
        private final String size;

        private final List<String> tried = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final Map<String, Long> spend = new LinkedHashMap<>();
        private int index;
        private int calls;
        private long coinsCharged;

        Attempt(ChannelHandlerContext ctx, ProxyConfig config, EventLoopGroup group,
                 ProviderHealthTracker healthTracker, AicoinLedger ledger, String wallet,
                 Capability capability, String prompt, String background, String subject,
                 List<String> ranked, boolean escalate, String voice, String size) {
            this.ctx = ctx;
            this.config = config;
            this.group = group;
            this.healthTracker = healthTracker;
            this.ledger = ledger;
            this.wallet = wallet;
            this.capability = capability;
            this.prompt = prompt;
            this.background = background;
            this.subject = subject;
            this.ranked = ranked;
            this.escalate = escalate;
            this.voice = voice;
            this.size = size;
        }

        void next() {
            if (index >= ranked.size() || index >= MAX_ATTEMPTS) {
                sendFailure();
                return;
            }
            String provider = ranked.get(index++);
            String model = modelFor(config, capability, provider);
            tried.add(provider);

            String path;
            List<Map.Entry<String, String>> headers;
            byte[] requestBody;
            String billingModel;
            if (capability == Capability.TEXT) {
                SharedContext context = new SharedContext(prompt, background,
                        config.getConsortium().getMaxContextChars());
                path = ChatAdapter.path(provider, model);
                headers = ChatAdapter.headers(provider);
                requestBody = ChatAdapter.body(provider, model,
                        escalate ? ConsortiumPrompts.singleSystem() : ConsortiumPrompts.pollSystem(),
                        context.forTurn(escalate ? ConsortiumPrompts.singleTask() : ConsortiumPrompts.pollTask()),
                        config.getConsortium().getMaxOutputTokens());
                // Chat responses report their own usage, which is a truer figure than any rate
                // this side could assume.
                billingModel = null;
            } else {
                MediaAdapter.Request request = capability == Capability.IMAGE
                        ? MediaAdapter.image(provider, model, prompt, size)
                        : MediaAdapter.audio(provider, model, voiceFor(provider), prompt);
                path = request.path();
                headers = request.headers();
                requestBody = request.body();
                billingModel = model;
            }

            BilledCall.post(group, config, healthTracker, ledger, wallet, provider, path, headers,
                    requestBody, billingModel, outcome -> {
                        if (outcome.isInsufficient()) {
                            sendInsufficient();
                            return;
                        }
                        if (outcome.wasAttempted()) {
                            calls++;
                        }
                        if (outcome.getError() != null) {
                            errors.add("{\"provider\":" + Json.string(provider)
                                    + ",\"error\":" + Json.string(outcome.getError()) + "}");
                            next();
                            return;
                        }
                        coinsCharged += outcome.getCharged();
                        spend.merge(provider, outcome.getCharged(), Long::sum);
                        if (capability == Capability.TEXT) {
                            onText(provider, model, outcome.getResponse());
                        } else {
                            onMedia(provider, model, outcome.getResponse());
                        }
                    });
        }

        private String voiceFor(String provider) {
            String requested = voice == null ? "" : voice.trim();
            return requested.isEmpty() ? config.getCapabilities().voiceFor(provider) : requested;
        }

        private void onText(String provider, String model, UpstreamCall.Result result) {
            String text = ChatAdapter.text(provider, result.bodyText());
            if (text == null) {
                // A 2xx carrying no text — a model that spent its cap thinking. Paid for, since the
                // provider did the work, and the next provider gets a turn.
                errors.add("{\"provider\":" + Json.string(provider)
                        + ",\"error\":\"no text in response\"}");
                next();
                return;
            }
            if (escalate && ConsortiumPrompts.isEscalation(text)) {
                List<String> panel = ranked.size() > 1 ? ranked : List.of(provider);
                if (panel.size() < 2) {
                    // Nobody to escalate to. Answering with the marker would be absurd, so the
                    // request is put back to the same model without the escape hatch.
                    errors.add("{\"provider\":" + Json.string(provider)
                            + ",\"error\":\"asked for the panel, but no other provider is available\"}");
                    sendText(provider, model, text, false);
                    return;
                }
                ConsortiumHandler.escalate(ctx, config, group, healthTracker, ledger, wallet,
                        prompt, background, panel, panel.get(0), subject, capability.path(),
                        calls, coinsCharged, spend);
                return;
            }
            sendText(provider, model, text, false);
        }

        private void onMedia(String provider, String model, UpstreamCall.Result result) {
            MediaAdapter.Media media = MediaAdapter.read(capability, provider, result.body());
            if (media == null) {
                errors.add("{\"provider\":" + Json.string(provider)
                        + ",\"error\":\"no " + capability.path() + " in response\"}");
                next();
                return;
            }
            StringBuilder json = new StringBuilder();
            json.append("{\"media_base64\":").append(Json.string(media.base64()))
                    .append(",\"media_type\":").append(Json.string(media.mediaType()));
            appendRouting(json, provider, model);
            json.append("}");
            send(HttpResponseStatus.OK, json.toString());
        }

        private void sendText(String provider, String model, String answer, boolean escalated) {
            StringBuilder json = new StringBuilder();
            json.append("{\"answer\":").append(Json.string(answer))
                    .append(",\"escalated\":").append(escalated);
            appendRouting(json, provider, model);
            json.append("}");
            send(HttpResponseStatus.OK, json.toString());
        }

        /** The part of every response that says how the request was routed and what it cost. */
        private void appendRouting(StringBuilder json, String provider, String model) {
            json.append(",\"capability\":").append(Json.string(capability.path()))
                    .append(",\"subject\":").append(Json.string(subject))
                    .append(",\"provider\":").append(Json.string(provider))
                    .append(",\"model\":").append(Json.string(model))
                    .append(",\"considered\":[");
            for (int i = 0; i < ranked.size(); i++) {
                json.append(i == 0 ? "" : ",").append(Json.string(ranked.get(i)));
            }
            json.append("],\"calls\":").append(calls)
                    .append(",\"coins_charged\":").append(coinsCharged)
                    .append(",\"errors\":[").append(String.join(",", errors)).append("]");
        }

        private void sendFailure() {
            send(HttpResponseStatus.BAD_GATEWAY,
                    "{\"error\":\"no provider answered\",\"capability\":" + Json.string(capability.path())
                            + ",\"subject\":" + Json.string(subject)
                            + ",\"tried\":[" + joinQuoted(tried) + "]"
                            + ",\"calls\":" + calls
                            + ",\"coins_charged\":" + coinsCharged
                            + ",\"errors\":[" + String.join(",", errors) + "]}");
        }

        private void sendInsufficient() {
            ledger.getBalance(wallet, balance -> send(HttpResponseStatus.PAYMENT_REQUIRED,
                    "{\"error\":\"insufficient aicoin balance\",\"balance\":"
                            + (balance.isPresent() ? balance.get() : 0) + "}"));
        }

        private void send(HttpResponseStatus status, String json) {
            byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            // The same header a single proxied call sets — a client shows what a call cost without
            // knowing how the proxy bills.
            response.headers().set("X-Aicoin-Charged", Long.toString(coinsCharged));
            HttpUtil.setContentLength(response, bytes.length);
            ctx.writeAndFlush(response);
        }

        private static String joinQuoted(List<String> values) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < values.size(); i++) {
                out.append(i == 0 ? "" : ",").append(Json.string(values.get(i)));
            }
            return out.toString();
        }
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":" + Json.string(message) + "}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
