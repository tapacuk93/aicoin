package com.aicoin.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * {@code GET /admin/transactions} — the paid calls the proxy recorded (see {@link Transactions}),
 * newest first, {@code X-Admin-Token} gated like every other admin path.
 *
 * <p>Query parameters, all optional: {@code limit} (1-200, default 50), {@code before} (the
 * {@code next_before} of the previous page), and the filters {@code app}, {@code purpose},
 * {@code ok} ({@code true}/{@code false}) and {@code wallet}. The {@code totals} are for the whole
 * filtered set, not the page.
 */
final class TransactionsHandler {

    private TransactionsHandler() {
    }

    static void serve(ChannelHandlerContext ctx, FullHttpRequest request, ProxyConfig config) {
        if (!AppCapsHandler.isAuthorized(request, config, ctx)) {
            return;
        }
        Transactions transactions = Transactions.get();
        if (transactions == null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    AppCapsHandler.error("transactions are not recorded on this server"));
            return;
        }
        Object parsed = Transactions.Query.parse(new QueryStringDecoder(request.uri()).parameters());
        if (parsed instanceof String problem) {
            AppCapsHandler.send(ctx, HttpResponseStatus.BAD_REQUEST, AppCapsHandler.error(problem));
            return;
        }
        transactions.query((Transactions.Query) parsed, json -> {
            if (json.isEmpty()) {
                AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                        AppCapsHandler.error("could not read transactions"));
            } else {
                AppCapsHandler.send(ctx, HttpResponseStatus.OK, json.get());
            }
        });
    }
}
