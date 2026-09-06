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

    static void respond(ChannelHandlerContext ctx, ProxyConfig config, ProviderLiveness liveness) {
        byte[] bytes = buildJson(config, liveness).getBytes(CharsetUtil.UTF_8);
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
                List<String> ranked = CapabilityHandler.rank(config, liveness, capability, subject);
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
        json.append("},\"escalation\":").append(config.getCapabilities().isEscalationEnabled()).append("}");
        return json.toString();
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
