package com.aicoin.proxy;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves {@code GET /wallet}: the bundled static wallet page, per
 * CONTRACT.md's "Wallet web page" section. Read fresh from the classpath
 * resource on every request (same "no caching surprises" posture as
 * {@link FreeCoinsCounter}) rather than cached at startup.
 */
final class WalletPageHandler {

    private static final String RESOURCE_NAME = "/wallet.html";

    private WalletPageHandler() {
    }

    static void respond(ChannelHandlerContext ctx) {
        byte[] html = readResource();
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(html));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        HttpUtil.setContentLength(response, html.length);
        ctx.writeAndFlush(response);
    }

    private static byte[] readResource() {
        try (InputStream in = WalletPageHandler.class.getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                return "<html><body>wallet page missing</body></html>"
                        .getBytes(StandardCharsets.UTF_8);
            }
            return ByteBufUtil.getBytes(Unpooled.wrappedBuffer(in.readAllBytes()));
        } catch (IOException e) {
            return ("<html><body>error loading wallet page: " + e.getMessage() + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
