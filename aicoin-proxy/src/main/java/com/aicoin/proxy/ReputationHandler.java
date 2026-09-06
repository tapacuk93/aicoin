package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code GET /wallet/api/reputation/{address}} — what anybody can know about a wallet before
 * dealing with it, per CONTRACT.md's "Reputation".
 *
 * <p>Every transfer is settled by the ledger before it is a transfer at all, so nobody has to weigh
 * a stranger's standing to know whether they have been paid. What this is for is the decision
 * <em>around</em> a payment: whether to send a large amount to a wallet nobody has ever dealt with,
 * and whether an address somebody has just read out belongs to an account with anything behind it.
 */
final class ReputationHandler {

    /** How many counterparties are looked at when weighing a rating. */
    private static final int MAX_COUNTERPARTIES_WEIGHED = 10;

    private ReputationHandler() {
    }

    static void serve(ChannelHandlerContext ctx, AicoinLedger ledger, String address) {
        if (!address.matches("[0-9a-fA-F]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "address must be 64 hex characters");
            return;
        }
        String wallet = address.toLowerCase(Locale.ROOT);
        ledger.getBalance(wallet, balance -> {
            if (!balance.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                return;
            }
            List<String> counterparties = new ArrayList<>();
            ledger.walletSummary(wallet, counterparties, summary -> {
                Map<String, Long> counts = new LinkedHashMap<>(summary.orElse(Map.of()));
                // How many of those counterparties are wallets with something to lose. Bounded:
                // this walks other wallets' records, and a rating is looked up in front of somebody
                // deciding whether to press send.
                ledger.solidCounterparties(counterparties, MAX_COUNTERPARTIES_WEIGHED, solid -> {
                    counts.put("solid_counterparties", solid);
                    respond(ctx, wallet, balance.get(), counts);
                });
            });
        });
    }

    private static void respond(ChannelHandlerContext ctx, String wallet, double held, Map<String, Long> counts) {
        long now = System.currentTimeMillis();
        int rating = Reputation.score(held, counts, now);
        StringBuilder reasons = new StringBuilder("[");
        boolean first = true;
        for (String reason : Reputation.reasons(held, counts, now)) {
            reasons.append(first ? "" : ",").append(Json.string(reason));
            first = false;
        }
        reasons.append("]");
        sendJson(ctx, "{\"address\":\"" + wallet + "\""
                + ",\"balance\":" + held
                + ",\"owed\":" + (held < 0 ? -held : 0)
                + ",\"rating\":" + rating
                + ",\"purchases\":" + counts.getOrDefault("purchases", 0L)
                + ",\"calls\":" + counts.getOrDefault("calls", 0L)
                + ",\"counterparties\":" + counts.getOrDefault("counterparties", 0L)
                + ",\"solid_counterparties\":" + counts.getOrDefault("solid_counterparties", 0L)
                + ",\"first_seen\":" + counts.getOrDefault("first_seen", 0L)
                + ",\"reasons\":" + reasons + "}");
    }

    private static void sendJson(ChannelHandlerContext ctx, String json) {
        send(ctx, HttpResponseStatus.OK, json);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        send(ctx, status, "{\"error\":\"" + message + "\"}");
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
