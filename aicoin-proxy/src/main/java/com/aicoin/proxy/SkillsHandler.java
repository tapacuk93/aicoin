package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /skills}: the ratings {@code POST /text}, {@code /image} and {@code /audio} route on,
 * and — for each capability and subject — who would actually get the call right now.
 *
 * <p>Routing a request on a caller's behalf is only defensible if the caller can find out how. The
 * ratings alone would not do it: they are an opinion, and what a request is served by is that
 * opinion filtered through which providers this deployment has keys for and which of them are
 * answering. So the ranking is published as it stands, live, and not as the table it came from.
 *
 * <p>Public and unauthenticated, like {@code /price} and {@code /health}: it is a description of
 * the service, and a caller deciding whether to use it should not have to hold a wallet first.
 */
final class SkillsHandler {

    private SkillsHandler() {
    }

    static void respond(ChannelHandlerContext ctx, ProxyConfig config, ProviderLiveness liveness,
                         ProviderSpeed speed) {
        byte[] bytes = buildJson(config, liveness, speed).getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        // Fetched cross-origin by the landing page, same as /price and /health.
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    /** Pure JSON body construction, exposed for testing without a Netty channel. */
    static String buildJson(ProxyConfig config, ProviderLiveness liveness) {
        return buildJson(config, liveness, null);
    }

    static String buildJson(ProxyConfig config, ProviderLiveness liveness, ProviderSpeed speed) {
        ProviderSkills skills = config.getCapabilities().getSkills();
        StringBuilder json = new StringBuilder();
        json.append("{\"subjects\":[");
        List<String> subjects = SubjectTagger.subjects();
        for (int i = 0; i < subjects.size(); i++) {
            json.append(i == 0 ? "" : ",").append(Json.string(subjects.get(i)));
        }
        json.append("],\"providers\":[");
        boolean firstProvider = true;
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            Map<String, Integer> capabilityRatings = skills.capabilities().getOrDefault(provider, Map.of());
            Map<String, Integer> subjectRatings = skills.subjects().getOrDefault(provider, Map.of());
            if (capabilityRatings.isEmpty() && subjectRatings.isEmpty()) {
                continue;
            }
            json.append(firstProvider ? "" : ",");
            firstProvider = false;
            json.append("{\"name\":").append(Json.string(provider))
                    .append(",\"capabilities\":").append(ratingsJson(capabilityRatings))
                    .append(",\"subjects\":").append(ratingsJson(subjectRatings)).append("}");
        }
        json.append("],\"routing\":{");
        boolean firstCapability = true;
        for (Capability capability : Capability.values()) {
            json.append(firstCapability ? "" : ",");
            firstCapability = false;
            json.append(Json.string(capability.path())).append(":{");
            boolean firstSubject = true;
            for (String subject : subjects) {
                List<String> ranked = CapabilityHandler.rank(config, liveness, speed, capability, subject);
                json.append(firstSubject ? "" : ",");
                firstSubject = false;
                json.append(Json.string(subject)).append(":[");
                for (int i = 0; i < ranked.size(); i++) {
                    json.append(i == 0 ? "" : ",").append(Json.string(ranked.get(i)));
                }
                json.append("]");
            }
            json.append("}");
        }
        json.append("},\"observed_ms\":{");
        // What the ordering above was actually based on. A ranked list with no numbers behind it
        // is an assertion; with them it is a decision somebody else can check - and when a
        // provider is ordered lower than its rating suggests, this is the reason why.
        boolean firstObserved = true;
        for (Capability capability : Capability.values()) {
            Map<String, Long> medians = speed == null ? Map.of() : speed.observed(capability);
            if (medians.isEmpty()) {
                continue;
            }
            json.append(firstObserved ? "" : ",");
            firstObserved = false;
            json.append(Json.string(capability.path())).append(":{");
            boolean firstProviderSeen = true;
            for (Map.Entry<String, Long> entry : medians.entrySet()) {
                json.append(firstProviderSeen ? "" : ",")
                        .append(Json.string(entry.getKey())).append(":").append(entry.getValue());
                firstProviderSeen = false;
            }
            json.append("}");
        }
        json.append("},\"escalation\":").append(config.getCapabilities().isEscalationEnabled());
        appendModels(json, config, liveness);
        json.append("}");
        return json.toString();
    }

    /**
     * {@code "models"}: which model stands behind each provider right now, per capability, and who
     * would sit on a {@code POST /consortium} panel.
     *
     * <p>A caller that keeps what a model wrote needs this to know when that writing has been
     * overtaken: a reader app holding a chapter written by an older model can offer to have it
     * rewritten only if it can tell the model it was written by is no longer the one answering.
     * The provider name alone cannot tell it that — "anthropic" is the same string across every
     * model Anthropic ships — and the model ids live in config, so they change with a restart and
     * no release; publishing them is the only way a client can see that happen.
     *
     * <p>Each capability lists the providers its routing would consider, with the model a call
     * would actually send, from {@link CapabilityHandler#models} — the same filter the {@code
     * routing} block above applies, so a provider absent there is absent here.
     *
     * <p>The panel is the one {@link ConsortiumHandler#panel} builds for a call that names nobody:
     * keyed, with a model, in {@link ChatAdapter#CHAT_PROVIDERS} order. Like that call it does not
     * consult liveness, so neither does this. The editor is the configured one if it is on the
     * panel; otherwise a call's editor is the panelist ranked first <em>for that call's subject</em>,
     * and what is published is that choice for {@link SubjectTagger#GENERAL} — the subject a request
     * with nothing more specific about it is tagged as. An empty panel and a null editor when the
     * endpoint is disabled, since then nobody sits on it.
     */
    private static void appendModels(StringBuilder json, ProxyConfig config, ProviderLiveness liveness) {
        json.append(",\"models\":{");
        boolean firstCapability = true;
        for (Capability capability : Capability.values()) {
            json.append(firstCapability ? "" : ",");
            firstCapability = false;
            json.append(Json.string(capability.path())).append(":{");
            boolean firstProvider = true;
            for (Map.Entry<String, String> entry : CapabilityHandler.models(config, liveness, capability).entrySet()) {
                json.append(firstProvider ? "" : ",")
                        .append(Json.string(entry.getKey())).append(":").append(Json.string(entry.getValue()));
                firstProvider = false;
            }
            json.append("}");
        }
        ConsortiumConfig consortium = config.getConsortium();
        List<String> panel = consortium.isEnabled() ? ConsortiumHandler.panel(config, null) : List.of();
        json.append(",\"consortium\":{\"panel\":[");
        for (int i = 0; i < panel.size(); i++) {
            json.append(i == 0 ? "" : ",")
                    .append(Json.string(panel.get(i) + ":" + consortium.modelFor(panel.get(i)).trim()));
        }
        String editor = panel.isEmpty() ? null : ConsortiumHandler.editor(config,
                ConsortiumHandler.panel(config, null, SubjectTagger.GENERAL, null), null);
        json.append("],\"editor\":").append(editor == null ? "null" : Json.string(editor)).append("}}");
    }

    private static String ratingsJson(Map<String, Integer> ratings) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : ratings.entrySet()) {
            json.append(first ? "" : ",").append(Json.string(entry.getKey())).append(":").append(entry.getValue());
            first = false;
        }
        return json.append("}").toString();
    }
}
