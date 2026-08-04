package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;

/**
 * Per CONTRACT.md's "Wallet web page" section: {@code GET /wallet} serves
 * the bundled static page verbatim.
 */
class WalletPageHandlerTest {

    @Test
    void servesBundledHtmlPage() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        WalletPageHandler.respond(channel.pipeline().firstContext());
        FullHttpResponse response = channel.readOutbound();
        String body = response.content().toString(CharsetUtil.UTF_8);
        assertTrue(body.contains("<!DOCTYPE html>"));
        assertTrue(body.contains("aicoin wallet"));
        assertTrue(body.contains("/wallet/api/balance/"));
        assertTrue(body.contains("/wallet/api/claim"));
        assertTrue(body.contains("/wallet/api/transfer"));
        response.release();
    }
}
