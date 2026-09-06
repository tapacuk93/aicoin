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

/**
 * Handles the proxy-side {@code GET /health} endpoint, per CONTRACT.md's
 * "Additional proxy-side endpoints" section: reports, for every configured
 * provider (all of {@link ProxyConfig#PROVIDER_NAMES}, always, in a stable
 * order, even ones with zero traffic so far), whether its rolling window of
 * recent forwarded calls (tracked by {@link ProviderHealthTracker}) has hit
 * a rate-limit (429) or budget (402/403) error, and whether the proxy has a
 * real (non-empty) {@code apiKey} configured for it at all ({@code enabled})
 * — the landing page uses this to show which AI backends are actually live.
 *
 * <p>Those three fields are all inferences from traffic this proxy happened to
 * send, so they say nothing at all about a provider nobody has called lately.
 * {@code state} is the answer to the question they cannot answer: it comes from
 * {@link ProviderLiveness}, which asks each provider directly on a timer, and
 * is one of {@code alive}, {@code down}, {@code unconfigured} (no key) or
 * {@code unknown} (not asked yet). {@code detail} says what produced it,
 * {@code checkedAt} is when, in epoch millis, and {@code latencyMs} is how long
 * that took.
 */
final class HealthHandler {

    private HealthHandler() {
    }

    static void respond(ChannelHandlerContext ctx, ProviderHealthTracker tracker, ProviderLiveness liveness,
                         ProxyConfig config) {
        byte[] bytes = buildJson(tracker, liveness, config).getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    /** Pure JSON body construction, exposed for testing without a Netty channel. */
    static String buildJson(ProviderHealthTracker tracker, ProviderLiveness liveness, ProxyConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\":[");
        boolean first = true;
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            ProviderHealthTracker.Health health = tracker.healthFor(provider);
            String apiKey = config.getProvider(provider).getApiKey();
            boolean enabled = apiKey != null && !apiKey.isEmpty();
            ProviderLiveness.Probe probe = liveness.probeFor(provider);
            sb.append("{\"name\":\"").append(provider).append("\",")
                    .append("\"enabled\":").append(enabled).append(",")
                    .append("\"healthy\":").append(health.isHealthy()).append(",")
                    .append("\"rateLimited\":").append(health.isRateLimited()).append(",")
                    .append("\"overBudget\":").append(health.isOverBudget()).append(",")
                    .append("\"state\":\"").append(probe.getState()).append("\",")
                    .append("\"detail\":").append(Json.string(probe.getDetail())).append(",")
                    .append("\"checkedAt\":").append(probe.getCheckedAtMillis()).append(",")
                    .append("\"latencyMs\":").append(probe.getLatencyMs()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
