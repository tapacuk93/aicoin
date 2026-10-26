package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.util.Map;

/**
 * {@code POST /push/devices} and {@code DELETE /push/devices}, per CONTRACT.md's "Push
 * notifications": a wallet registering, or forgetting, a device to be told when its durable calls
 * finish. See {@link PushDevices}.
 */
final class PushHandler {

    private PushHandler() {
    }

    static void serve(ChannelHandlerContext ctx, byte[] body, boolean register, String wallet) {
        PushDevices devices = PushDevices.get();
        if (devices == null) {
            ConsortiumHandler.sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "push is not available");
            return;
        }
        Object parsed;
        try {
            parsed = Json.load(new String(body, CharsetUtil.UTF_8));
        } catch (Exception e) {
            parsed = null;
        }
        if (!(parsed instanceof Map)) {
            ConsortiumHandler.sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must be JSON");
            return;
        }
        Object device = PushDevices.parse((Map<?, ?>) parsed);
        if (device instanceof String) {
            ConsortiumHandler.sendError(ctx, HttpResponseStatus.BAD_REQUEST, (String) device);
            return;
        }
        PushDevices.Device d = (PushDevices.Device) device;
        (register ? devices.register(wallet, d) : devices.unregister(wallet, d)).whenComplete((v, err) -> {
            if (err != null) {
                ConsortiumHandler.sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not store the device");
                return;
            }
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
            HttpUtil.setContentLength(response, 0);
            ctx.writeAndFlush(response);
        });
    }
}
