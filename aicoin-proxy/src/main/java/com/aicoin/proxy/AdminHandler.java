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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Serves the operator-only wallet/transaction admin surface, per CONTRACT.md's
 * "Admin page" section: {@code GET /admin} (a bundled static page, no auth on
 * the page itself, same posture as {@code GET /wallet}), and the two data
 * endpoints it calls, {@code GET /admin/wallets} and {@code GET
 * /admin/wallets/{address}/transactions} — both of which reveal every known
 * wallet's balance and full transaction history, so unlike every other
 * endpoint in this proxy they require an {@code X-Admin-Token} header
 * matching {@link ProxyConfig#getAdminToken()}. That token is empty by
 * default, which disables this entire surface (every data endpoint responds
 * {@code 503}) until an operator deliberately sets {@code
 * AICOIN_PROXY_ADMIN_TOKEN} — the admin page must never be reachable by
 * accident on a freshly deployed instance.
 */
final class AdminHandler {

    private static final String RESOURCE_NAME = "/admin.html";
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private AdminHandler() {
    }

    static void servePage(ChannelHandlerContext ctx) {
        byte[] html = readResource();
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(html));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        HttpUtil.setContentLength(response, html.length);
        ctx.writeAndFlush(response);
    }

    static void serveWallets(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        ledger.listWalletSummaries(summaries -> {
            if (!summaries.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not list wallets");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"wallets\":[");
            boolean first = true;
            for (AicoinLedger.WalletSummary summary : summaries.get()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("{\"address\":\"").append(summary.getAddress()).append("\",")
                        .append("\"balance\":").append(formatNumber(summary.getBalance())).append(",")
                        .append("\"transaction_count\":").append(summary.getTransactionCount()).append("}");
            }
            sb.append("]}");
            sendJson(ctx, sb.toString());
        });
    }

    static void serveTransactions(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger,
                                   ProxyConfig config, String address) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        if (!isValidAddress(address)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "invalid address");
            return;
        }
        ledger.getTransactions(address, transactions -> {
            if (!transactions.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load transactions");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"address\":\"").append(address).append("\",\"transactions\":[");
            sb.append(String.join(",", transactions.get()));
            sb.append("]}");
            sendJson(ctx, sb.toString());
        });
    }

    /** @return true if authorized; on false, has already written the appropriate 401/503 response. */
    private static boolean isAuthorized(FullHttpRequest request, ProxyConfig config, ChannelHandlerContext ctx) {
        String configuredToken = config.getAdminToken();
        if (configuredToken == null || configuredToken.isEmpty()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "admin disabled");
            return false;
        }
        String providedToken = request.headers().get(ADMIN_TOKEN_HEADER);
        if (providedToken == null || !constantTimeEquals(configuredToken, providedToken)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "missing or invalid X-Admin-Token");
            return false;
        }
        return true;
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isValidAddress(String address) {
        if (address.length() != 64) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean isHexChar = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHexChar) {
                return false;
            }
        }
        return true;
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
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static byte[] readResource() {
        try (InputStream in = AdminHandler.class.getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                return "<html><body>admin page missing</body></html>".getBytes(StandardCharsets.UTF_8);
            }
            return ByteBufUtil.getBytes(Unpooled.wrappedBuffer(in.readAllBytes()));
        } catch (IOException e) {
            return ("<html><body>error loading admin page: " + e.getMessage() + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
