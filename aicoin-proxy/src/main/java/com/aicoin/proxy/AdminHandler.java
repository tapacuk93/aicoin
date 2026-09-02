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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the operator-only wallet/transaction admin surface, per CONTRACT.md's
 * "Admin page" section: {@code GET /admin} (a bundled static page, no auth on
 * the page itself, same posture as {@code GET /wallet}), and the two data
 * endpoints it calls, {@code GET /admin/wallets} and {@code GET
 * /admin/wallets/{address}/transactions} — both of which reveal every known
 * wallet's balance and full transaction history, so unlike every other
 * endpoint in this proxy they require an {@code X-Admin-Token} header
 * matching {@link ProxyConfig#getAdminToken()}. That token is empty by
 * default, which disables this entire surface (every data endpoint responds
 * {@code 503}) until an operator deliberately sets {@code
 * AICOIN_PROXY_ADMIN_TOKEN} — the admin page must never be reachable by
 * accident on a freshly deployed instance.
 */
final class AdminHandler {

    private static final String RESOURCE_NAME = "/admin.html";
    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    /** How many merged entries {@code /admin/activity} returns. */
    private static final int ACTIVITY_LIMIT = 200;
    /**
     * How many of each wallet's most recent entries the merge considers. Above the per-page limit
     * on purpose, so one busy wallet can't crowd every other wallet out of the feed.
     */
    private static final int ACTIVITY_PER_WALLET_SCAN = 50;

    private AdminHandler() {
    }

    static void servePage(ChannelHandlerContext ctx) {
        byte[] html = readResource();
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(html));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        HttpUtil.setContentLength(response, html.length);
        ctx.writeAndFlush(response);
    }

    static void serveWallets(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        ledger.listWalletSummaries(summaries -> {
            if (!summaries.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not list wallets");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"wallets\":[");
            boolean first = true;
            for (AicoinLedger.WalletSummary summary : summaries.get()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("{\"address\":\"").append(summary.getAddress()).append("\",")
                        .append("\"balance\":").append(formatNumber(summary.getBalance())).append(",")
                        .append("\"transaction_count\":").append(summary.getTransactionCount()).append("}");
            }
            sb.append("]}");
            sendJson(ctx, sb.toString());
        });
    }

    /**
     * {@code GET /admin/activity} — every wallet's recent transactions in one newest-first feed,
     * each entry carrying the wallet it belongs to. Paid calls are in here as their {@code debit}
     * entries, so this is the call log as well as the money log.
     *
     * <p>Answers the question the per-wallet view can't: what is happening across the whole system
     * right now, without knowing which address to look at first.
     */
    static void serveActivity(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger,
                              ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        ledger.listRecentTransactions(ACTIVITY_LIMIT, ACTIVITY_PER_WALLET_SCAN, entries -> {
            if (!entries.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load activity");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"activity\":[");
            boolean first = true;
            for (AicoinLedger.GlobalTxEntry entry : entries.get()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                // The stored entry is already a JSON object; splice the wallet
                // in rather than re-encoding what the ledger wrote.
                sb.append("{\"address\":\"").append(entry.getAddress()).append("\",\"tx\":")
                        .append(entry.getJson()).append("}");
            }
            sb.append("]}");
            sendJson(ctx, sb.toString());
        });
    }

    static void serveTransactions(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger,
                                   ProxyConfig config, String address) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        if (!isValidAddress(address)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "invalid address");
            return;
        }
        ledger.getTransactions(address, transactions -> {
            if (!transactions.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load transactions");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"address\":\"").append(address).append("\",\"transactions\":[");
            sb.append(String.join(",", transactions.get()));
            sb.append("]}");
            sendJson(ctx, sb.toString());
        });
    }

    /**
     * Ceiling on one credit. Not a policy about how many coins may exist — the operator can call
     * this again — but a guard against a fat-fingered zero turning a top-up into a number nobody
     * meant to type.
     */
    static final double MAX_CREDIT_AICOIN = 1_000_000;

    /**
     * {@code POST /admin/credit} — the operator's own way to put coins in a wallet, per
     * CONTRACT.md's "Admin credit". Body {@code {"address":"<64 hex>","amount":1000}}, optionally
     * with {@code "reason"} (recorded in the wallet's transaction log) and {@code "reference"} (a
     * key that makes the credit idempotent — the same reference credits once, however many times
     * it is sent).
     *
     * <p>Nothing backs these coins. They are the operator saying "I will pay for the calls these
     * buy", which is why this is admin-token-only and why each one is written into the wallet's
     * transaction log as an {@code admin_credit} rather than quietly adjusting a number.
     */
    static void serveCredit(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger,
                             ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        String body = new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8);
        String address = stringField(body, "address");
        if (address == null || !isValidAddress(address)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "address must be 64 hex characters");
            return;
        }
        Matcher amountMatcher = Pattern.compile("\"amount\"\\s*:\\s*(-?[0-9.]+)").matcher(body);
        if (!amountMatcher.find()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must carry an amount");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountMatcher.group(1));
        } catch (NumberFormatException e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "amount must be a number");
            return;
        }
        if (!(amount > 0)) {
            // Taking coins away is not this endpoint's job: a negative credit would be a debit with
            // no call behind it, and no way for a wallet holder to see what it paid for.
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "amount must be positive");
            return;
        }
        if (amount > MAX_CREDIT_AICOIN) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST,
                    "amount must be at most " + (long) MAX_CREDIT_AICOIN + " in one credit");
            return;
        }
        String reason = stringField(body, "reason");
        if (reason == null) {
            reason = "admin credit";
        }
        String reference = stringField(body, "reference");
        if (reference == null) {
            // No reference means "credit now", and a retry of that is another credit — the same as
            // running the command twice. A caller that cannot tolerate that sends one.
            reference = java.util.UUID.randomUUID().toString();
        }

        String finalReason = reason;
        ledger.creditWallet(address, amount, reason, reference, result -> {
            if (!result.isReachable()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not credit the wallet");
                return;
            }
            sendJson(ctx, "{\"address\":\"" + address + "\",\"amount\":" + amount
                    + ",\"credited\":" + result.isCredited()
                    + ",\"balance\":" + result.getBalance()
                    + ",\"reason\":\"" + finalReason.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
        });
    }

    /** Pulls a JSON string field out of a small admin body, in the same regex idiom as the rest of these handlers. */
    static String stringField(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** @return true if authorized; on false, has already written the appropriate 401/503 response. */
    private static boolean isAuthorized(FullHttpRequest request, ProxyConfig config, ChannelHandlerContext ctx) {
        String configuredToken = config.getAdminToken();
        if (configuredToken == null || configuredToken.isEmpty()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "admin disabled");
            return false;
        }
        String providedToken = request.headers().get(ADMIN_TOKEN_HEADER);
        if (providedToken == null || !constantTimeEquals(configuredToken, providedToken)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "missing or invalid X-Admin-Token");
            return false;
        }
        return true;
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static boolean isValidAddress(String address) {
        if (address.length() != 64) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            char c = address.charAt(i);
            boolean isHexChar = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHexChar) {
                return false;
            }
        }
        return true;
    }

    private static String formatNumber(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static void sendJson(ChannelHandlerContext ctx, String json) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static byte[] readResource() {
        try (InputStream in = AdminHandler.class.getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                return "<html><body>admin page missing</body></html>".getBytes(StandardCharsets.UTF_8);
            }
            return ByteBufUtil.getBytes(Unpooled.wrappedBuffer(in.readAllBytes()));
        } catch (IOException e) {
            return ("<html><body>error loading admin page: " + e.getMessage() + "</body></html>")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
