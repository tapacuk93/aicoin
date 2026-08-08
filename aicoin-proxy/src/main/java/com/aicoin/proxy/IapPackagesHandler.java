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
import io.netty.util.CharsetUtil;

/**
 * Serves the IAP coin-package list, per CONTRACT.md's "Coin packages" section: {@code GET
 * /iap/packages} (public, {@code Access-Control-Allow-Origin: *}, same posture as {@code GET
 * /price} — every client app fetches this at launch/paywall-open time instead of hardcoding coin
 * amounts) and {@code POST /admin/iap/packages} (the one write path, gated the same way as every
 * other {@code /admin/*} endpoint — see {@link AdminHandler} — since it's the operator surface
 * that changes what every app is currently selling).
 */
final class IapPackagesHandler {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private IapPackagesHandler() {
    }

    /** Public, {@code Access-Control-Allow-Origin: *} — same posture as {@code GET /price}. */
    static void servePackages(ChannelHandlerContext ctx, AicoinLedger ledger, ProxyConfig config) {
        String seedJson = IapPackages.seedJson(config.getIapPackages());
        ledger.getIapPackages(seedJson, packagesJson -> {
            if (!packagesJson.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load iap packages", true);
                return;
            }
            sendJson(ctx, "{\"packages\":" + packagesJson.get() + "}", true);
        });
    }

    /** Admin-token gated, no CORS — same posture as every other {@code /admin/*} endpoint (see {@link AdminHandler}). */
    static void serveAdminSet(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        String body = new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8);
        IapPackages.ValidationResult validated = IapPackages.validate(body);
        if (!validated.isValid()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, validated.getError(), false);
            return;
        }
        String packagesJson = IapPackages.toJson(validated.getEntries());
        ledger.setIapPackages(packagesJson, ok -> {
            if (!ok) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not update iap packages", false);
                return;
            }
            sendJson(ctx, "{\"packages\":" + packagesJson + "}", false);
        });
    }

    /** @return true if authorized; on false, has already written the appropriate 401/503 response — same posture as {@link AdminHandler}'s two data endpoints. */
    private static boolean isAuthorized(FullHttpRequest request, ProxyConfig config, ChannelHandlerContext ctx) {
        String configuredToken = config.getAdminToken();
        if (configuredToken == null || configuredToken.isEmpty()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "admin disabled", false);
            return false;
        }
        String providedToken = request.headers().get(ADMIN_TOKEN_HEADER);
        if (providedToken == null || !AdminHandler.constantTimeEquals(configuredToken, providedToken)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "missing or invalid X-Admin-Token", false);
            return false;
        }
        return true;
    }

    private static void sendJson(ChannelHandlerContext ctx, String json, boolean cors) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        if (cors) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message, boolean cors) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        if (cors) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
