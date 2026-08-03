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
 * a rate-limit (429) or budget (402/403) error.
 */
final class HealthHandler {

    private HealthHandler() {
    }

    static void respond(ChannelHandlerContext ctx, ProviderHealthTracker tracker) {
        byte[] bytes = buildJson(tracker).getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    /** Pure JSON body construction, exposed for testing without a Netty channel. */
    static String buildJson(ProviderHealthTracker tracker) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\":[");
        boolean first = true;
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            ProviderHealthTracker.Health health = tracker.healthFor(provider);
            sb.append("{\"name\":\"").append(provider).append("\",")
                    .append("\"healthy\":").append(health.isHealthy()).append(",")
                    .append("\"rateLimited\":").append(health.isRateLimited()).append(",")
                    .append("\"overBudget\":").append(health.isOverBudget()).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }
}
