package com.aicoin.proxy;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Serves {@code GET /price/chart} (a bundled static page graphing how
 * {@code price_usd} arrived at its current value) and the JSON data
 * endpoint behind it, {@code GET /price/history} — both public, read-only,
 * no auth, same posture as {@code GET /price} itself (which this
 * complements rather than replaces: {@code /price} stays the single-value
 * snapshot every other page already fetches).
 */
final class PriceChartHandler {

    private static final String RESOURCE_NAME = "/price-chart.html";
    private static final int DEFAULT_POINTS = 60;
    private static final int MAX_POINTS = 500;

    private PriceChartHandler() {
    }

    static void servePage(ChannelHandlerContext ctx) {
        byte[] html = readResource();
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(html));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        HttpUtil.setContentLength(response, html.length);
        ctx.writeAndFlush(response);
    }

    static void serveHistory(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        int numPoints = parsePointsParam(request.uri());
        ledger.computePriceHistory(numPoints, config.getDecayHalflifeDays(), points -> {
            if (!points.isPresent()) {
                sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, "could not compute price history");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"half_life_days\":").append(formatNumber(config.getDecayHalflifeDays())).append(",\"points\":[");
            boolean first = true;
            for (AicoinLedger.PricePoint point : points.get()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("{\"at\":").append(point.getAtMillis())
                        .append(",\"price_usd\":").append(point.getPriceUsd()).append("}");
            }
            sb.append("]}");
            sendJson(ctx, sb.toString());
        });
    }

    static int parsePointsParam(String uri) {
        Map<String, List<String>> params = new QueryStringDecoder(uri).parameters();
        List<String> values = params.get("points");
        if (values == null || values.isEmpty()) {
            return DEFAULT_POINTS;
        }
        try {
            int requested = Integer.parseInt(values.get(0).trim());
            return Math.max(2, Math.min(requested, MAX_POINTS));
        } catch (NumberFormatException e) {
            return DEFAULT_POINTS;
        }
    }

    private static String formatNumber(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static void sendJson(ChannelHandlerContext ctx, String json) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static byte[] readResource() {
        try (InputStream in = PriceChartHandler.class.getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                return "<html><body>price chart page missing</body></html>".getBytes(StandardCharsets.UTF_8);
            }
            return ByteBufUtil.getBytes(Unpooled.wrappedBuffer(in.readAllBytes()));
        } catch (IOException e) {
            return ("<html><body>error loading price chart page: " + e.getMessage() + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
