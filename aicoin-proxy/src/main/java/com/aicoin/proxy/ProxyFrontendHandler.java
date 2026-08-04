package com.aicoin.proxy;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * Inbound routing/forwarding handler, per CONTRACT.md's "Routing" section:
 * the client calls the proxy at the exact same path a real provider would
 * use; the {@code X-AI} request header selects which {@code
 * providers.&lt;name&gt;} config entry to use. Also serves the wallet
 * ledger endpoints ({@code GET /price}, {@code GET /wallet/api/*}) directly
 * against {@link AicoinLedger}, plus {@code GET /free-coins/available} and
 * {@code GET /health}.
 */
public class ProxyFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(ProxyFrontendHandler.class.getName());
    private static final String X_AI_HEADER = "X-AI";
    private static final String X_API_KEY_HEADER = WalletValidation.HEADER_NAME;

    private final ProxyConfig config;
    private final EventLoopGroup clientGroup;
    private final ProviderHealthTracker healthTracker;
    private final AicoinLedger ledger;

    public ProxyFrontendHandler(ProxyConfig config, EventLoopGroup clientGroup, ProviderHealthTracker healthTracker,
                                 AicoinLedger ledger) {
        this.config = config;
        this.clientGroup = clientGroup;
        this.healthTracker = healthTracker;
        this.ledger = ledger;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = pathOnly(request.uri());

        if (request.method() == HttpMethod.GET && "/price".equals(path)) {
            sendPrice(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/free-coins/available".equals(path)) {
            sendFreeCoinsAvailable(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/health".equals(path)) {
            HealthHandler.respond(ctx, healthTracker, config);
            return;
        }
        if (request.method() == HttpMethod.GET && "/wallet".equals(path)) {
            WalletPageHandler.respond(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && path.startsWith("/wallet/api/balance/")) {
            String walletId = path.substring("/wallet/api/balance/".length());
            ledger.getBalance(walletId, balance -> {
                if (!balance.isPresent()) {
                    sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
                    return;
                }
                byte[] bytes = ("{\"user_id\":" + jsonString(walletId) + ",\"balance\":" + formatNumber(balance.get()) + "}")
                        .getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.OK, bytes);
            });
            return;
        }
        if (request.method() == HttpMethod.POST && "/wallet/api/claim".equals(path)) {
            handleClaim(ctx, ByteBufUtil.getBytes(request.content()));
            return;
        }
        if (request.method() == HttpMethod.POST && "/wallet/api/transfer".equals(path)) {
            handleTransfer(ctx, ByteBufUtil.getBytes(request.content()));
            return;
        }

        Optional<String> walletIdOpt = WalletValidation.extractWalletId(request.headers().get(X_API_KEY_HEADER));
        if (!walletIdOpt.isPresent()) {
            sendJsonError(ctx, HttpResponseStatus.UNAUTHORIZED, "missing X-Api-Key (wallet id)");
            return;
        }
        String walletId = walletIdOpt.get();

        Optional<String> providerOpt = ProviderRouting.resolve(request.headers().get(X_AI_HEADER));
        if (!providerOpt.isPresent()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "missing or unknown X-AI header");
            return;
        }
        String provider = providerOpt.get();
        ProviderConfig providerConfig = config.getProvider(provider);

        AuthInjector.Injection injection = AuthInjector.compute(providerConfig);

        List<Map.Entry<String, String>> forwardHeaders = new ArrayList<>();
        for (Map.Entry<String, String> h : request.headers()) {
            String name = h.getKey();
            if (name.equalsIgnoreCase(X_AI_HEADER)
                    || name.equalsIgnoreCase(X_API_KEY_HEADER)
                    || name.equalsIgnoreCase(HttpHeaderNames.HOST.toString())
                    || name.equalsIgnoreCase(HttpHeaderNames.CONTENT_LENGTH.toString())
                    || name.equalsIgnoreCase(HttpHeaderNames.AUTHORIZATION.toString())
                    || (providerConfig.getAuthHeader() != null && name.equalsIgnoreCase(providerConfig.getAuthHeader()))) {
                continue;
            }
            forwardHeaders.add(h);
        }

        String forwardUri = request.uri();
        if (injection.isQueryParam()) {
            forwardUri = AuthInjector.appendQueryParam(forwardUri, injection.getName(), injection.getValue());
        } else {
            forwardHeaders.add(new AbstractMap.SimpleEntry<>(injection.getName(), injection.getValue()));
        }
        String finalForwardUri = forwardUri;

        byte[] bodyBytes = ByteBufUtil.getBytes(request.content());
        HttpMethod method = request.method();

        ledger.getBalance(walletId, balance -> {
            WalletValidation.BalanceDecision decision = WalletValidation.decide(balance);
            if (decision.shouldProceed()) {
                UpstreamForwarder.forward(clientGroup, config, healthTracker, ledger, ctx, method, finalForwardUri,
                        forwardHeaders, bodyBytes, providerConfig.getBaseUrl(), provider);
            } else if (decision.isUnreachable()) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
            } else {
                sendInsufficientBalance(ctx, decision.getBalance());
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "error handling inbound request", cause);
        ctx.close();
    }

    private void sendPrice(ChannelHandlerContext ctx) {
        ledger.computePrice(config.getDecayHalflifeDays(), price -> {
            if (price == null) {
                byte[] bytes = "{\"error\":\"could not compute price\"}".getBytes(CharsetUtil.UTF_8);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR, Unpooled.wrappedBuffer(bytes));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
                HttpUtil.setContentLength(response, bytes.length);
                ctx.writeAndFlush(response);
                return;
            }
            byte[] bytes = ("{\"price_usd\":" + price.getPriceUsd()
                    + ",\"total_spend_usd\":" + price.getTotalSpendUsd()
                    + ",\"weighted_total\":" + price.getWeightedTotal()
                    + ",\"half_life_days\":" + price.getHalfLifeDays() + "}").getBytes(CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            HttpUtil.setContentLength(response, bytes.length);
            ctx.writeAndFlush(response);
        });
    }

    private void handleClaim(ChannelHandlerContext ctx, byte[] body) {
        Optional<String> userIdOpt = parseUserId(body);
        if (!userIdOpt.isPresent()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "missing user_id");
            return;
        }
        String userId = userIdOpt.get();
        ledger.claimFreeCoins(userId, config.getFreeClaimCooldownSeconds(), result -> {
            if (!result.isReachable()) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
                return;
            }
            String nextEligibleAt = result.getNextEligibleAt().toString();
            if (result.isGranted()) {
                byte[] bytes = ("{\"granted\":true,\"next_eligible_at\":" + jsonString(nextEligibleAt) + "}")
                        .getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.OK, bytes);
            } else {
                byte[] bytes = ("{\"granted\":false,\"next_eligible_at\":" + jsonString(nextEligibleAt) + "}")
                        .getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, bytes);
            }
        });
    }

    private void handleTransfer(ChannelHandlerContext ctx, byte[] body) {
        Object parsed;
        try {
            parsed = new Yaml().load(new String(body, CharsetUtil.UTF_8));
        } catch (Exception e) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "invalid request body");
            return;
        }
        if (!(parsed instanceof Map)) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "invalid request body");
            return;
        }
        Map<?, ?> map = (Map<?, ?>) parsed;
        Object fromRaw = map.get("from_user_id");
        Object toRaw = map.get("to_user_id");
        Object amountRaw = map.get("amount");
        if (!(fromRaw instanceof String) || ((String) fromRaw).isEmpty()
                || !(toRaw instanceof String) || ((String) toRaw).isEmpty()
                || !(amountRaw instanceof Number)) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "from_user_id, to_user_id, and amount are required");
            return;
        }
        String from = (String) fromRaw;
        String to = (String) toRaw;
        double amount = ((Number) amountRaw).doubleValue();

        ledger.transfer(from, to, amount, result -> {
            if (!result.isReachable()) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
            } else if (result.isSuccess()) {
                sendJson(ctx, HttpResponseStatus.OK, "{}".getBytes(CharsetUtil.UTF_8));
            } else {
                sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "insufficient balance");
            }
        });
    }

    private void sendFreeCoinsAvailable(ChannelHandlerContext ctx) {
        int available = FreeCoinsCounter.readAvailable(config.getFreeCoinsCounterFile());
        byte[] bytes = ("{\"available\":" + available + "}").getBytes(CharsetUtil.UTF_8);
        sendJson(ctx, HttpResponseStatus.OK, bytes);
    }

    private static Optional<String> parseUserId(byte[] body) {
        try {
            Object parsed = new Yaml().load(new String(body, CharsetUtil.UTF_8));
            if (parsed instanceof Map) {
                Object userId = ((Map<?, ?>) parsed).get("user_id");
                if (userId instanceof String && !((String) userId).isEmpty()) {
                    return Optional.of((String) userId);
                }
            }
        } catch (Exception e) {
            // falls through to empty
        }
        return Optional.empty();
    }

    private static String pathOnly(String uri) {
        if (uri == null) {
            return "";
        }
        int qIdx = uri.indexOf('?');
        return qIdx >= 0 ? uri.substring(0, qIdx) : uri;
    }

    private static void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, byte[] bytes) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendJsonError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        sendJson(ctx, status, ("{\"error\":" + jsonString(message) + "}").getBytes(CharsetUtil.UTF_8));
    }

    /**
     * {@code 402 {"error":"insufficient aicoin balance","balance":<value>}}, per
     * CONTRACT.md's "Auth — wallet id IS the API key, gated on a positive
     * balance" section: sent instead of forwarding when the wallet's
     * reported balance is {@code <= 0}. No upstream call is made and no
     * event is emitted, same as the 401/503 short-circuit cases above.
     */
    private static void sendInsufficientBalance(ChannelHandlerContext ctx, Double balance) {
        byte[] bytes = ("{\"error\":\"insufficient aicoin balance\",\"balance\":" + formatNumber(balance) + "}")
                .getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.PAYMENT_REQUIRED, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    /**
     * Renders a {@link Number} the way a whole-number-valued JSON field
     * typically looks (no trailing {@code .0}).
     */
    private static String formatNumber(Number n) {
        double d = n.doubleValue();
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

