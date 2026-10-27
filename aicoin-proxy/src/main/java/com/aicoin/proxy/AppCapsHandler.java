package com.aicoin.proxy;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /admin/apps}, {@code PUT /admin/apps/{app}} and {@code DELETE /admin/apps/{app}} —
 * the operator's view and control of per-app daily spend caps, per CONTRACT.md's "Per-app daily
 * caps". {@code X-Admin-Token} gated like every other admin path.
 */
final class AppCapsHandler {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private AppCapsHandler() {
    }

    static void serveList(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        ledger.appCaps().list(Instant.now(), apps -> {
            if (apps.isEmpty()) {
                send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, error("could not list apps"));
                return;
            }
            withWalletCoins(ledger, apps.get(), () -> {
                StringBuilder sb = new StringBuilder("{\"apps\":[");
                for (int i = 0; i < apps.get().size(); i++) {
                    sb.append(i == 0 ? "" : ",").append(apps.get().get(i).toJson());
                }
                send(ctx, HttpResponseStatus.OK, sb.append("]}").toString());
            });
        });
    }

    /**
     * Looks up the coins each app's wallets hold (a server wallet that runs dry gets {@code 402}
     * whatever its app's cap says), then runs {@code then}. A failed lookup leaves that wallet null.
     */
    static void withWalletCoins(AicoinLedger ledger, List<AppCaps.AppStatus> apps, Runnable then) {
        List<String> wallets = new ArrayList<>();
        for (AppCaps.AppStatus app : apps) {
            wallets.addAll(app.getConfig().getWallets());
        }
        Map<String, Double> coins = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(wallets.size());
        Runnable finish = () -> {
            for (AppCaps.AppStatus app : apps) {
                if (!app.getConfig().getWallets().isEmpty()) {
                    app.setWalletCoins(coins);
                }
            }
            then.run();
        };
        if (wallets.isEmpty()) {
            finish.run();
            return;
        }
        for (String w : wallets) {
            ledger.getBalance(w, balance -> {
                balance.ifPresent(b -> coins.put(w, b));
                if (pending.decrementAndGet() == 0) {
                    finish.run();
                }
            });
        }
    }

    /**
     * {@code POST /admin/apps/{app}/top-up} with {@code {"usd":5}} adds a one-time top-up for
     * today (UTC); {@code DELETE} clears today's. Returns the app as {@code GET} lists it.
     */
    static void serveTopUp(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config,
                           String app) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        if (!AppCaps.isValidAppName(app)) {
            send(ctx, HttpResponseStatus.BAD_REQUEST, error("invalid app name"));
            return;
        }
        Double usd = null;
        if (request.method() != HttpMethod.DELETE) {
            String body = new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8);
            Object parsed;
            try {
                parsed = Json.load(body);
            } catch (RuntimeException e) {
                parsed = null;
            }
            String problem = topUpProblem(parsed);
            if (problem != null) {
                send(ctx, HttpResponseStatus.BAD_REQUEST, error(problem));
                return;
            }
            usd = ((Number) ((Map<?, ?>) parsed).get("usd")).doubleValue();
        }
        ledger.appCaps().topUp(app, usd, Instant.now(), result -> {
            if (!result.isReachable()) {
                send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, error("could not top up app"));
            } else if ("no such app".equals(result.getRefusal())) {
                send(ctx, HttpResponseStatus.NOT_FOUND, error(result.getRefusal()));
            } else if (result.getRefusal() != null) {
                send(ctx, HttpResponseStatus.CONFLICT, error(result.getRefusal()));
            } else {
                withWalletCoins(ledger, List.of(result.getStatus()),
                        () -> send(ctx, HttpResponseStatus.OK, result.getStatus().toJson()));
            }
        });
    }

    /** Why a top-up body is refused, or null: it must be {@code {"usd":N}} with 0 < N <= 1000. */
    static String topUpProblem(Object parsed) {
        if (!(parsed instanceof Map<?, ?> map) || !(map.get("usd") instanceof Number n)) {
            return "body must be {\"usd\":N}";
        }
        double usd = n.doubleValue();
        if (!(usd > 0) || !Double.isFinite(usd) || usd > AppCaps.MAX_TOP_UP_USD) {
            return "usd must be a positive number of at most " + (int) AppCaps.MAX_TOP_UP_USD;
        }
        return null;
    }

    /** What a PUT body asks for, parsed and validated; {@code error} is set when it is refused. */
    static final class PutRequest {
        boolean setCap;
        Double usdPerDay;
        List<String> wallets;
        String error;
    }

    /**
     * Body {@code {"usd_per_day":20,"wallets":["<64 hex>",...]}}. {@code usd_per_day} absent keeps
     * the cap, null removes it, otherwise it must be a positive number. {@code wallets} absent keeps
     * the mapping, otherwise replaces it.
     */
    static PutRequest parsePut(String body) {
        PutRequest out = new PutRequest();
        Object parsed;
        try {
            parsed = body == null || body.isBlank() ? Map.of() : Json.load(body);
        } catch (RuntimeException e) {
            out.error = "body must be JSON";
            return out;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            out.error = "body must be a JSON object";
            return out;
        }
        if (map.containsKey("usd_per_day")) {
            out.setCap = true;
            Object raw = map.get("usd_per_day");
            if (raw == null) {
                out.usdPerDay = null;
            } else if (raw instanceof Number n && n.doubleValue() > 0 && Double.isFinite(n.doubleValue())) {
                out.usdPerDay = n.doubleValue();
            } else {
                out.error = "usd_per_day must be a positive number, or null for no cap";
                return out;
            }
        }
        if (map.containsKey("wallets")) {
            if (!(map.get("wallets") instanceof List<?> list)) {
                out.error = "wallets must be an array of wallet addresses";
                return out;
            }
            out.wallets = new ArrayList<>();
            for (Object w : list) {
                if (!(w instanceof String s) || !AdminHandler.isValidAddress(s)) {
                    out.error = "each wallet must be 64 hex characters";
                    return out;
                }
                out.wallets.add(s);
            }
        }
        return out;
    }

    static void serveWrite(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config,
                           String app) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        if (!AppCaps.isValidAppName(app)) {
            send(ctx, HttpResponseStatus.BAD_REQUEST,
                    error("app must be 1-64 characters of a-z, 0-9, '.', '_' or '-', starting with a letter or digit"));
            return;
        }
        if (request.method() == HttpMethod.DELETE) {
            ledger.appCaps().delete(app, deleted -> {
                if (deleted.isEmpty()) {
                    send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, error("could not delete app"));
                } else if (!deleted.get()) {
                    send(ctx, HttpResponseStatus.NOT_FOUND, error("no such app"));
                } else {
                    send(ctx, HttpResponseStatus.OK, "{\"deleted\":" + Json.string(app) + "}");
                }
            });
            return;
        }
        // Read now: the request is released when channelRead0 returns.
        PutRequest put = parsePut(new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8));
        if (put.error != null) {
            send(ctx, HttpResponseStatus.BAD_REQUEST, error(put.error));
            return;
        }
        ledger.appCaps().put(app, put.setCap, put.usdPerDay, put.wallets, Instant.now(), result -> {
            if (!result.isReachable()) {
                send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, error("could not update app"));
            } else if (result.getConflict() != null) {
                send(ctx, HttpResponseStatus.CONFLICT, error(result.getConflict()));
            } else {
                withWalletCoins(ledger, List.of(result.getStatus()),
                        () -> send(ctx, HttpResponseStatus.OK, result.getStatus().toJson()));
            }
        });
    }

    static String error(String message) {
        return "{\"error\":" + Json.string(message) + "}";
    }

    static boolean isAuthorized(FullHttpRequest request, ProxyConfig config, ChannelHandlerContext ctx) {
        String configuredToken = config.getAdminToken();
        if (configuredToken == null || configuredToken.isEmpty()) {
            send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, error("admin disabled"));
            return false;
        }
        String providedToken = request.headers().get(ADMIN_TOKEN_HEADER);
        if (providedToken == null || !AdminHandler.constantTimeEquals(configuredToken, providedToken)) {
            send(ctx, HttpResponseStatus.UNAUTHORIZED, error("missing or invalid X-Admin-Token"));
            return false;
        }
        return true;
    }

    static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
