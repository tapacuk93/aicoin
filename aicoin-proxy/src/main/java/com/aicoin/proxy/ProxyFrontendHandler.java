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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inbound routing/forwarding handler, per CONTRACT.md's "Routing" section:
 * the client calls the proxy at the exact same path a real provider would
 * use; the {@code X-AI} request header selects which {@code
 * providers.&lt;name&gt;} config entry to use. Also serves the three
 * proxy-side endpoints, {@code GET /price}, {@code GET
 * /free-coins/available}, and {@code GET /health}.
 */
public class ProxyFrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(ProxyFrontendHandler.class.getName());
    private static final String X_AI_HEADER = "X-AI";
    private static final String X_API_KEY_HEADER = WalletValidation.HEADER_NAME;

    private final ProxyConfig config;
    private final EventLoopGroup clientGroup;
    private final ProviderHealthTracker healthTracker;

    public ProxyFrontendHandler(ProxyConfig config, EventLoopGroup clientGroup, ProviderHealthTracker healthTracker) {
        this.config = config;
        this.clientGroup = clientGroup;
        this.healthTracker = healthTracker;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = pathOnly(request.uri());

        if (request.method() == HttpMethod.GET && "/price".equals(path)) {
            PriceForwarder.forward(clientGroup, config.getPriceUrl(), ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/free-coins/available".equals(path)) {
            sendFreeCoinsAvailable(ctx);
            return;
        }
        if (request.method() == HttpMethod.GET && "/health".equals(path)) {
            HealthHandler.respond(ctx, healthTracker);
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

        WalletValidator.validate(clientGroup, config.getBalanceUrlBase(), walletId, reachable -> {
            if (Boolean.TRUE.equals(reachable)) {
                UpstreamForwarder.forward(clientGroup, config, healthTracker, ctx, method, finalForwardUri,
                        forwardHeaders, bodyBytes, providerConfig.getBaseUrl(), provider, walletId);
            } else {
                sendJsonError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not validate wallet");
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "error handling inbound request", cause);
        ctx.close();
    }

    private void sendFreeCoinsAvailable(ChannelHandlerContext ctx) {
        int available = FreeCoinsCounter.readAvailable(config.getFreeCoinsCounterFile());
        byte[] bytes = ("{\"available\":" + available + "}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static String pathOnly(String uri) {
        if (uri == null) {
            return "";
        }
        int qIdx = uri.indexOf('?');
        return qIdx >= 0 ? uri.substring(0, qIdx) : uri;
    }

    private static void sendJsonError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
