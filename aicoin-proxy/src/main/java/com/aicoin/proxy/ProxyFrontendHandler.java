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
import java.util.function.Consumer;
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
 *
 * <p>Two independent auth schemes gate mutating requests, per CONTRACT.md's
 * auth sections: wallet-management actions (claim/transfer/revoke-tokens)
 * require a live {@link WalletSignature#verifyLive} signature over each
 * request; the generic {@code X-AI}-routed proxy path requires a {@link
 * WalletSignature#verifyToken} API token instead, since routine AI-proxy
 * traffic needs to work from any HTTP client without implementing
 * per-request signing.
 */
public class ProxyFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(ProxyFrontendHandler.class.getName());
    private static final String X_AI_HEADER = "X-AI";
    private static final String X_API_KEY_HEADER = WalletValidation.HEADER_NAME;
    private static final String X_API_SIGNATURE_HEADER = "X-Api-Signature";
    private static final String X_API_TIMESTAMP_HEADER = "X-Api-Timestamp";

    /** 1 aicoin is worth 1 paid AI call — the currency's fixed exchange rate, not a tunable. */
    private static final double CALL_COST_AICOIN = 1.0;
    /**
     * What a call to a {@link FreeTargets free target} costs: nothing. Passed to {@link
     * UpstreamForwarder} in place of {@link #CALL_COST_AICOIN} so it knows there is no debit to
     * refund on failure and no paid call to feed the price formula on success.
     */
    private static final double FREE_TARGET_COST_AICOIN = 0.0;
    private static final double FREE_CLAIM_AMOUNT_AICOIN = 10.0;

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
        if (request.method() == HttpMethod.GET && "/price/chart".equals(path)) {
            PriceChartHandler.servePage(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/price/history".equals(path)) {
            PriceChartHandler.serveHistory(ctx, request, ledger, config);
            return;
        }
        if (request.method() == HttpMethod.GET && "/free-coins/available".equals(path)) {
            sendFreeCoinsAvailable(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/iap/packages".equals(path)) {
            IapPackagesHandler.servePackages(ctx, ledger, config);
            return;
        }
        if (request.method() == HttpMethod.POST && "/admin/iap/packages".equals(path)) {
            IapPackagesHandler.serveAdminSet(ctx, request, ledger, config);
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
            byte[] body = ByteBufUtil.getBytes(request.content());
            requireLiveSignature(ctx, request, body, address -> handleClaim(ctx, address));
            return;
        }
        if (request.method() == HttpMethod.POST && "/wallet/api/transfer".equals(path)) {
            byte[] body = ByteBufUtil.getBytes(request.content());
            requireLiveSignature(ctx, request, body, address -> handleTransfer(ctx, address, body));
            return;
        }
        if (request.method() == HttpMethod.POST && "/wallet/api/revoke-tokens".equals(path)) {
            byte[] body = ByteBufUtil.getBytes(request.content());
            requireLiveSignature(ctx, request, body, address -> handleRevokeTokens(ctx, address));
            return;
        }
        if (request.method() == HttpMethod.POST && "/wallet/api/redeem-iap".equals(path)) {
            handleRedeemIap(ctx, ByteBufUtil.getBytes(request.content()));
            return;
        }
        if (request.method() == HttpMethod.GET && "/admin".equals(path)) {
            AdminHandler.servePage(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/admin/activity".equals(path)) {
            AdminHandler.serveActivity(ctx, request, ledger, config);
            return;
        }
        if (request.method() == HttpMethod.GET && "/admin/wallets".equals(path)) {
            AdminHandler.serveWallets(ctx, request, ledger, config);
            return;
        }
        if (request.method() == HttpMethod.GET && path.startsWith("/admin/wallets/") && path.endsWith("/transactions")) {
            String address = path.substring("/admin/wallets/".length(), path.length() - "/transactions".length());
            AdminHandler.serveTransactions(ctx, request, ledger, config, address);
            return;
        }

        Optional<String> apiKeyOpt = WalletValidation.extractWalletId(request.headers().get(X_API_KEY_HEADER));
        if (!apiKeyOpt.isPresent()) {
            sendJsonError(ctx, HttpResponseStatus.UNAUTHORIZED, "missing X-Api-Key (API token)");
            return;
        }
        String apiKeyHeader = apiKeyOpt.get();
        Optional<String> peekedAddress = WalletSignature.peekTokenAddress(apiKeyHeader);
        if (!peekedAddress.isPresent()) {
            sendJsonError(ctx, HttpResponseStatus.UNAUTHORIZED, "invalid API token");
            return;
        }

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

        // A free target (model/voice listing, token counting, account lookup) costs the proxy
        // nothing upstream, so it costs the wallet nothing here — see FreeTargets. Auth still
        // applies: it's the proxy's own paid key going out either way.
        boolean freeTarget = FreeTargets.isFree(method.name(), path, providerConfig.getFreePaths());

        ledger.getTokenRevokedBefore(peekedAddress.get(), revokedBefore -> {
            WalletSignature.AuthResult authResult = WalletSignature.verifyToken(
                    apiKeyHeader, Instant.now().toEpochMilli(), revokedBefore.orElse(null));
            if (!authResult.isValid()) {
                sendJsonError(ctx, HttpResponseStatus.UNAUTHORIZED, authResult.getFailureReason());
                return;
            }
            String walletAddress = authResult.getAddress();
            if (freeTarget) {
                UpstreamForwarder.forward(clientGroup, config, healthTracker, ledger, ctx, method, finalForwardUri,
                        forwardHeaders, bodyBytes, providerConfig.getBaseUrl(), provider,
                        walletAddress, FREE_TARGET_COST_AICOIN);
                return;
            }
            ledger.debitForCall(walletAddress, CALL_COST_AICOIN, provider, debit -> {
                if (!debit.isReachable()) {
                    sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
                } else if (!debit.isSuccess()) {
                    sendInsufficientBalance(ctx, debit.getBalance());
                } else {
                    UpstreamForwarder.forward(clientGroup, config, healthTracker, ledger, ctx, method, finalForwardUri,
                            forwardHeaders, bodyBytes, providerConfig.getBaseUrl(), provider,
                            walletAddress, CALL_COST_AICOIN);
                }
            });
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "error handling inbound request", cause);
        ctx.close();
    }

    /**
     * Verifies the three live-signature headers ({@code X-Api-Key},
     * {@code X-Api-Signature}, {@code X-Api-Timestamp}) against the given
     * request's method/path/body, per {@link WalletSignature#verifyLive}.
     * On success, invokes {@code onVerified} with the signer's address —
     * the only identity these wallet-management actions trust, never a
     * body field. On failure, responds {@code 401} with the specific
     * reason and never invokes {@code onVerified}.
     */
    private void requireLiveSignature(ChannelHandlerContext ctx, FullHttpRequest request, byte[] body,
                                       Consumer<String> onVerified) {
        String address = request.headers().get(X_API_KEY_HEADER);
        String signature = request.headers().get(X_API_SIGNATURE_HEADER);
        String timestamp = request.headers().get(X_API_TIMESTAMP_HEADER);
        String path = pathOnly(request.uri());

        WalletSignature.AuthResult result = WalletSignature.verifyLive(address, signature, timestamp,
                request.method().name(), path, body, Instant.now().toEpochMilli(), config.getSignatureSkewSeconds());
        if (!result.isValid()) {
            sendJsonError(ctx, HttpResponseStatus.UNAUTHORIZED, result.getFailureReason());
            return;
        }
        onVerified.accept(result.getAddress());
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

    private void handleClaim(ChannelHandlerContext ctx, String address) {
        ledger.claimFreeCoins(address, config.getFreeClaimCooldownSeconds(), config.getFreeCoinsPoolSize(), FREE_CLAIM_AMOUNT_AICOIN, result -> {
            if (!result.isReachable()) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
                return;
            }
            if (result.isPoolExhausted()) {
                byte[] bytes = "{\"granted\":false,\"reason\":\"pool_exhausted\"}".getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, bytes);
                return;
            }
            String nextEligibleAt = result.getNextEligibleAt().toString();
            if (result.isGranted()) {
                byte[] bytes = ("{\"granted\":true,\"amount\":" + formatNumber(FREE_CLAIM_AMOUNT_AICOIN)
                        + ",\"next_eligible_at\":" + jsonString(nextEligibleAt) + "}")
                        .getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.OK, bytes);
            } else {
                byte[] bytes = ("{\"granted\":false,\"reason\":\"cooldown\",\"next_eligible_at\":" + jsonString(nextEligibleAt) + "}")
                        .getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.TOO_MANY_REQUESTS, bytes);
            }
        });
    }

    private void handleTransfer(ChannelHandlerContext ctx, String fromAddress, byte[] body) {
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
        Object toRaw = map.get("to_user_id");
        Object amountRaw = map.get("amount");
        if (!(toRaw instanceof String) || ((String) toRaw).isEmpty() || !(amountRaw instanceof Number)) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "to_user_id and amount are required");
            return;
        }
        String to = (String) toRaw;
        double amount = ((Number) amountRaw).doubleValue();

        ledger.transfer(fromAddress, to, amount, result -> {
            if (!result.isReachable()) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
            } else if (result.isSuccess()) {
                sendJson(ctx, HttpResponseStatus.OK, "{}".getBytes(CharsetUtil.UTF_8));
            } else {
                sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "insufficient balance");
            }
        });
    }

    /**
     * {@code POST /wallet/api/redeem-iap}, per CONTRACT.md's "Redeeming a purchase" section: no
     * live wallet signature required (crediting a wallet can't harm anyone, same reasoning as the
     * free faucet) — the entire security burden is on {@link AppleJwsVerifier} proving the
     * <em>purchase</em> is real. Idempotent: a StoreKit retry of an already-redeemed {@code
     * transactionId} returns {@code 200} with the current (unchanged) balance and {@code
     * "credited":0}, never an error.
     */
    private void handleRedeemIap(ChannelHandlerContext ctx, byte[] body) {
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
        Object toRaw = map.get("to_user_id");
        Object signedTransactionRaw = map.get("signed_transaction");
        if (!(toRaw instanceof String) || ((String) toRaw).isEmpty()
                || !(signedTransactionRaw instanceof String) || ((String) signedTransactionRaw).isEmpty()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "to_user_id and signed_transaction are required");
            return;
        }
        String toUserId = (String) toRaw;

        AppleJwsVerifier.VerifyResult verified = AppleJwsVerifier.verify(
                (String) signedTransactionRaw, Instant.now().toEpochMilli());
        if (!verified.isValid()) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, verified.getFailureReason());
            return;
        }
        if (!IapPackages.isKnownBundleId(verified.getBundleId())) {
            sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "unknown bundleId");
            return;
        }

        String seedJson = IapPackages.seedJson(config.getIapPackages());
        ledger.getIapPackages(seedJson, packagesJson -> {
            if (!packagesJson.isPresent()) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
                return;
            }
            Optional<IapPackages.Entry> pkg = IapPackages.findByProductId(packagesJson.get(), verified.getProductId());
            if (!pkg.isPresent()) {
                sendJsonError(ctx, HttpResponseStatus.BAD_REQUEST, "unknown productId");
                return;
            }
            double coins = pkg.get().getCoins() * (double) verified.getQuantity();
            ledger.redeemIap(verified.getTransactionId(), toUserId, verified.getProductId(), coins, redeemResult -> {
                if (!redeemResult.isReachable()) {
                    sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
                    return;
                }
                double credited = redeemResult.isFreshCredit() ? coins : 0.0;
                byte[] bytes = ("{\"credited\":" + formatNumber(credited)
                        + ",\"balance\":" + formatNumber(redeemResult.getBalance()) + "}").getBytes(CharsetUtil.UTF_8);
                sendJson(ctx, HttpResponseStatus.OK, bytes);
            });
        });
    }

    private void handleRevokeTokens(ChannelHandlerContext ctx, String address) {
        ledger.revokeTokensBefore(address, Instant.now().toEpochMilli(), ok -> {
            if (!ok) {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not revoke tokens");
                return;
            }
            sendJson(ctx, HttpResponseStatus.OK, "{}".getBytes(CharsetUtil.UTF_8));
        });
    }

    private void sendFreeCoinsAvailable(ChannelHandlerContext ctx) {
        ledger.getFreeCoinsRemaining(config.getFreeCoinsPoolSize(), remaining -> {
            int available = remaining.orElse(0);
            byte[] bytes = ("{\"available\":" + available + "}").getBytes(CharsetUtil.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            HttpUtil.setContentLength(response, bytes.length);
            ctx.writeAndFlush(response);
        });
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
     * CONTRACT.md's "Balance gate" section: sent when {@link
     * AicoinLedger#debitForCall} finds less than {@link #CALL_COST_AICOIN}
     * available. No upstream call is made and no debit is applied, same as
     * the 401/503 short-circuit cases above.
     */
    private static void sendInsufficientBalance(ChannelHandlerContext ctx, double balance) {
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
