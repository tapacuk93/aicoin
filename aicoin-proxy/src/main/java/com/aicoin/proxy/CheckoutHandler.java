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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.Yaml;

/**
 * The card path: buying aicoin with a bank card on the web, rather than through Apple.
 *
 * <p>Two endpoints, and the asymmetry between them is the whole design:
 *
 * <ul>
 *   <li>{@code POST /checkout/session} takes a wallet address and returns a Stripe Checkout URL to
 *       send the buyer to. It grants nothing — creating a session is not a payment, so it needs no
 *       authentication, exactly like {@code POST /iap/offer/check}.
 *   <li>{@code POST /checkout/webhook} is where money becomes coins, and is therefore the only
 *       thing that must be got right. It is public and unauthenticated in the HTTP sense; what
 *       makes it safe is {@link StripeWebhookVerifier} over the <em>raw</em> body plus idempotency
 *       on the session id. Both are required: the signature stops forgery, the marker stops
 *       Stripe's own retries — which run for hours against any delivery that did not return 2xx —
 *       from paying out twice.
 * </ul>
 *
 * <p>What is sold is the current offer, the same single number every app displays, so the card and
 * IAP paths cannot drift into selling different amounts for the same money. No live offer means
 * nothing is for sale and the endpoint says so rather than inventing a price.
 *
 * <p>The buyer's address is carried in the session's {@code metadata} and comes back on the webhook
 * unchanged. It is not a secret and does not need to be: crediting a wallet can only help its
 * owner, the same reasoning {@code redeem-iap} already relies on. The coin amount is carried the
 * same way, pinned at session-creation time, so an offer changed mid-checkout still credits what
 * the buyer was shown.
 */
final class CheckoutHandler {

    private static final Logger LOG = Logger.getLogger(CheckoutHandler.class.getName());
    private static final String STRIPE_API = "https://api.stripe.com/v1/checkout/sessions";
    private static final String SIGNATURE_HEADER = "Stripe-Signature";

    /** Shared, since Stripe is one host and a client per request would leak connections. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private CheckoutHandler() {
    }

    /** {@code POST /checkout/session}: body {@code {"address":"<wallet>"}}. */
    static void createSession(ChannelHandlerContext ctx, FullHttpRequest request,
                              AicoinLedger ledger, ProxyConfig config) {
        if (config.getStripeSecretKey().isEmpty()) {
            // Nothing is configured to take money, so saying "unavailable" is the honest answer;
            // returning a URL that cannot charge would be worse than refusing.
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "card checkout is not configured");
            return;
        }

        String body = request.content().toString(CharsetUtil.UTF_8);
        Map<?, ?> parsed = parseJsonObject(body);
        String address = parsed == null ? null : str(parsed.get("address"));
        if (address == null || !AdminHandler.isValidAddress(address)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "missing or invalid address");
            return;
        }

        ledger.getOffer(offer -> {
            if (!offer.isPresent()) {
                sendError(ctx, HttpResponseStatus.CONFLICT, "nothing is currently on sale");
                return;
            }
            Map<?, ?> offerObj = parseJsonObject(offer.get());
            Long coins = offerObj == null ? null : asLong(offerObj.get("coins"));
            Double usd = offerObj == null ? null : asDouble(offerObj.get("usd_price"));
            if (coins == null || usd == null || coins <= 0 || usd <= 0) {
                sendError(ctx, HttpResponseStatus.CONFLICT, "nothing is currently on sale");
                return;
            }
            // Stripe charges in the currency's minor unit, and rounding must not silently lose a
            // cent in either direction, so the price is stated in cents from here on.
            long amountCents = Math.round(usd * 100);
            requestSession(ctx, config, address, coins, amountCents);
        });
    }

    private static void requestSession(ChannelHandlerContext ctx, ProxyConfig config,
                                       String address, long coins, long amountCents) {
        String form = "mode=payment"
                + "&success_url=" + enc(config.getCheckoutSuccessUrl())
                + "&cancel_url=" + enc(config.getCheckoutCancelUrl())
                + "&line_items[0][quantity]=1"
                + "&line_items[0][price_data][currency]=usd"
                + "&line_items[0][price_data][unit_amount]=" + amountCents
                + "&line_items[0][price_data][product_data][name]=" + enc(coins + " AICoin")
                + "&metadata[address]=" + enc(address)
                + "&metadata[coins]=" + coins;

        HttpRequest req = HttpRequest.newBuilder(URI.create(STRIPE_API))
                .header("Authorization", "Bearer " + config.getStripeSecretKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            // Back onto the channel's own thread: this completes on an HttpClient thread, and
            // Netty channel writes belong to the event loop that owns the channel.
            ctx.executor().execute(() -> {
                if (err != null || res == null) {
                    LOG.log(Level.WARNING, "stripe session creation failed", err);
                    sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "could not reach the payment processor");
                    return;
                }
                if (res.statusCode() < 200 || res.statusCode() >= 300) {
                    // Stripe's error body can quote the request, which included the secret key in a
                    // header but never in the body; even so, only the status is echoed onward.
                    LOG.log(Level.WARNING, "stripe session creation returned " + res.statusCode());
                    sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "payment processor rejected the request");
                    return;
                }
                Map<?, ?> session = parseJsonObject(res.body());
                String url = session == null ? null : str(session.get("url"));
                if (url == null) {
                    sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "payment processor returned no checkout url");
                    return;
                }
                sendJson(ctx, "{\"url\":\"" + url.replace("\"", "\\\"") + "\",\"coins\":" + coins
                        + ",\"amount_cents\":" + amountCents + "}");
            });
        });
    }

    /**
     * {@code POST /checkout/webhook}: Stripe's event delivery, and the only place a card payment
     * turns into coins.
     *
     * <p>Answers 200 for anything genuinely signed, including events this does not act on. A non-2xx
     * tells Stripe to retry, and retrying an event that will never be actionable just fills the
     * delivery log; a signature failure is the one case worth a 400, because it is either a
     * misconfiguration or someone probing.
     */
    static void webhook(ChannelHandlerContext ctx, FullHttpRequest request,
                        AicoinLedger ledger, ProxyConfig config) {
        String secret = config.getStripeWebhookSecret();
        // Fails closed: an unset secret cannot verify anything, and treating that as "skip the
        // check" would leave a wallet-crediting endpoint open to the internet.
        byte[] raw = ByteBufUtil.getBytes(request.content());
        String signature = request.headers().get(SIGNATURE_HEADER);
        if (!StripeWebhookVerifier.verify(signature, raw, secret, Instant.now().getEpochSecond())) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "invalid signature");
            return;
        }

        Map<?, ?> event = parseJsonObject(new String(raw, StandardCharsets.UTF_8));
        String type = event == null ? null : str(event.get("type"));
        if (!"checkout.session.completed".equals(type)) {
            sendJson(ctx, "{\"ignored\":true}");
            return;
        }
        Map<?, ?> data = event.get("data") instanceof Map ? (Map<?, ?>) event.get("data") : null;
        Map<?, ?> session = data != null && data.get("object") instanceof Map
                ? (Map<?, ?>) data.get("object") : null;
        if (session == null) {
            sendJson(ctx, "{\"ignored\":true}");
            return;
        }
        // A session can complete without being paid — an async payment method still settling. Only
        // "paid" is money in hand; anything else arrives again later as its own event if it ever
        // does settle.
        String paymentStatus = str(session.get("payment_status"));
        if (paymentStatus != null && !"paid".equals(paymentStatus)) {
            sendJson(ctx, "{\"ignored\":true}");
            return;
        }
        String sessionId = str(session.get("id"));
        Map<?, ?> metadata = session.get("metadata") instanceof Map ? (Map<?, ?>) session.get("metadata") : null;
        String address = metadata == null ? null : str(metadata.get("address"));
        Long coins = metadata == null ? null : asLong(metadata.get("coins"));
        if (sessionId == null || address == null || coins == null || coins <= 0
                || !AdminHandler.isValidAddress(address)) {
            LOG.warning("checkout webhook missing session id, address or coins");
            sendJson(ctx, "{\"ignored\":true}");
            return;
        }

        ledger.creditCheckout(sessionId, address, coins, result -> {
            if (!result.isReachable()) {
                // The one case that must NOT return 2xx: the payment is real and uncredited, so
                // Stripe's retry is exactly what should happen.
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "ledger unavailable");
                return;
            }
            sendJson(ctx, "{\"credited\":" + result.isFreshCredit() + ",\"balance\":" + result.getBalance() + "}");
        });
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** JSON via SnakeYAML, the same deliberate simplification {@link CostCalculator} documents. */
    private static Map<?, ?> parseJsonObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            Object parsed = new Yaml().load(json);
            return parsed instanceof Map ? (Map<?, ?>) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static void sendJson(ChannelHandlerContext ctx, String json) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        byte[] bytes = ("{\"error\":\"" + message + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
