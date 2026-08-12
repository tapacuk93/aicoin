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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The operator's total spend ceiling, per CONTRACT.md's "Spend budget": {@code GET /budget}
 * (public, {@code Access-Control-Allow-Origin: *}, same posture as {@code GET /price} — the
 * landing page and any dashboard read it cross-origin), {@code POST /admin/budget} (the one write
 * path for the ceiling), and {@code POST /admin/internal-wallets} (which wallets are exempt).
 *
 * <p>The ceiling exists to bound what the operator can be billed by upstream providers. When
 * production spend reaches it, {@code GET /iap/packages} serves an empty catalog and the paywall
 * goes empty — see {@link IapPackagesHandler}. It deliberately does <b>not</b> stop AI calls for
 * coins already sold: those are paid for, and refusing them would take money for a service and
 * then decline to render it.
 */
final class BudgetHandler {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private BudgetHandler() {
    }

    /** Public, {@code Access-Control-Allow-Origin: *} — same posture as {@code GET /price}. */
    static void serveBudget(ChannelHandlerContext ctx, AicoinLedger ledger) {
        ledger.computeBudget(result -> {
            if (!result.isReachable()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load budget", true);
                return;
            }
            Double limit = result.getLimitUsd();
            String remaining = limit == null ? "null" : String.valueOf(Math.max(0, limit - result.getProductionSpendUsd()));
            sendJson(ctx, "{\"budget_usd\":" + (limit == null ? "null" : limit)
                    + ",\"spend_usd\":" + result.getProductionSpendUsd()
                    + ",\"internal_spend_usd\":" + result.getInternalSpendUsd()
                    + ",\"remaining_usd\":" + remaining
                    + ",\"exhausted\":" + result.isExhausted() + "}", true);
        });
    }

    /**
     * {@code POST /admin/budget} — body {@code {"usd":200}} sets the ceiling, {@code {"usd":0}}
     * removes it (there is no such thing as a zero-dollar ceiling that still sells anything, so
     * zero is spelled the same way "unlimited" is elsewhere in this proxy: absent).
     */
    static void serveAdminSet(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        String body = new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8);
        Matcher m = Pattern.compile("\"usd\"\\s*:\\s*(-?[0-9.]+)").matcher(body);
        if (!m.find()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be {\\\"usd\\\":N}", false);
            return;
        }
        double usd;
        try {
            usd = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "usd must be a number", false);
            return;
        }
        if (usd < 0) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "usd must not be negative", false);
            return;
        }
        Double limit = usd == 0 ? null : usd;
        ledger.setBudget(limit, ok -> {
            if (!ok) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not update budget", false);
                return;
            }
            ledger.computeBudget(result -> sendJson(ctx, "{\"budget_usd\":" + (limit == null ? "null" : limit)
                    + ",\"spend_usd\":" + result.getProductionSpendUsd()
                    + ",\"exhausted\":" + result.isExhausted() + "}", false));
        });
    }

    /** {@code POST /admin/internal-wallets} — body {@code {"add":["addr"],"remove":["addr"]}}; either key may be omitted. */
    static void serveAdminInternalWallets(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        String body = new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8);
        List<String> add = parseStringArray(body, "add");
        List<String> remove = parseStringArray(body, "remove");
        if (add.isEmpty() && remove.isEmpty()) {
            ledger.listInternalWallets(members -> {
                if (!members.isPresent()) {
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not list internal wallets", false);
                    return;
                }
                sendJson(ctx, "{\"internal_wallets\":" + toJsonArray(members.get()) + "}", false);
            });
            return;
        }
        ledger.updateInternalWallets(add, remove, count -> {
            if (!count.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not update internal wallets", false);
                return;
            }
            ledger.listInternalWallets(members -> sendJson(ctx,
                    "{\"internal_wallets\":" + toJsonArray(members.orElse(new ArrayList<>())) + "}", false));
        });
    }

    /** Pulls {@code "key":["a","b"]} out of a small admin body without adding a JSON dependency, same posture as the other admin parsers here. */
    static List<String> parseStringArray(String body, String key) {
        List<String> out = new ArrayList<>();
        Matcher arr = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(body);
        if (!arr.find()) {
            return out;
        }
        Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(arr.group(1));
        while (item.find()) {
            out.add(item.group(1));
        }
        return out;
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /** @return true if authorized; on false, has already written the appropriate 401/503 — same posture as {@link IapPackagesHandler}. */
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
