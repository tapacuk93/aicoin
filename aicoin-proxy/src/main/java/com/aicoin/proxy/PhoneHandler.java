package com.aicoin.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import java.util.Map;

/**
 * {@code /phone/*}, per CONTRACT.md's "Hosted Anyphone". The wallet is the one the API token
 * proved — never a body field — so every phone a request can see or change is its own.
 */
final class PhoneHandler {

    private PhoneHandler() {
    }

    static void serve(ChannelHandlerContext ctx, HostedPhones phones, String method, String path, byte[] body,
                      String wallet) {
        HostedPhones.Reply immediate = null;
        java.util.function.Supplier<HostedPhones.Reply> work = null;
        switch (method + " " + path) {
            case "GET /phone/price":
                work = phones::price;
                break;
            case "GET /phone/status":
                work = () -> phones.status(wallet);
                break;
            case "POST /phone/start": {
                Map<?, ?> json = parse(body);
                Object tier = json == null ? null : json.get("tier");
                if (!(tier instanceof String)) {
                    immediate = badRequest("body must be {\"tier\":\"gpu\"|\"cpu\",\"restore\":true|false}");
                } else {
                    boolean restore = Boolean.TRUE.equals(json.get("restore"));
                    work = () -> phones.start(wallet, (String) tier, restore);
                }
                break;
            }
            case "POST /phone/suspend": {
                Map<?, ?> json = body.length == 0 ? Map.of() : parse(body);
                if (json == null) {
                    immediate = badRequest("body must be JSON");
                } else {
                    boolean force = Boolean.TRUE.equals(json.get("force"));
                    work = () -> phones.suspend(wallet, force);
                }
                break;
            }
            default:
                ConsortiumHandler.sendError(ctx, HttpResponseStatus.NOT_FOUND, "no such phone endpoint");
                return;
        }
        if (immediate != null) {
            write(ctx, immediate);
            return;
        }
        phones.submit(work, reply -> write(ctx, reply));
    }

    private static void write(ChannelHandlerContext ctx, HostedPhones.Reply reply) {
        ConsortiumHandler.sendJson(ctx, HttpResponseStatus.valueOf(reply.status()), reply.json());
    }

    private static HostedPhones.Reply badRequest(String message) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", message);
        return new HostedPhones.Reply(400, body);
    }

    private static Map<?, ?> parse(byte[] body) {
        try {
            Object parsed = Json.load(new String(body, CharsetUtil.UTF_8));
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }
}
