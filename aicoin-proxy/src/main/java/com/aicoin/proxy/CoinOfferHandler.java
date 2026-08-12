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
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

/**
 * Serves the current offer, per CONTRACT.md's "The current offer" section: {@code GET /iap/offer}
 * (public — what every app's paywall displays), {@code POST /iap/offer/check} (public — the
 * re-check an app makes immediately before opening Apple's purchase sheet, which pins the amount
 * it is about to show), and {@code POST /admin/iap/offer} (the operator's one write, gated the
 * same way as every other {@code /admin/*} endpoint — see {@link AdminHandler}).
 *
 * @see CoinOffer for the pricing/resolution math and why it rounds up
 */
final class CoinOfferHandler {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    /**
     * How long a pin issued by {@code /iap/offer/check} stays honourable. Long enough to cover
     * Apple's purchase sheet plus a slow network and a StoreKit retry or two; short enough that a
     * pin hoarded across an offer change can't be redeemed at a stale amount indefinitely. Not
     * config: it's a property of how long a purchase takes, not of a deployment.
     */
    static final long PIN_TTL_SECONDS = 900;

    /**
     * The same minimum-signal guard {@code scripts/adjust-iap-prices.sh} applies, for the same
     * reason: {@code price_usd} averages recorded paid calls, so a fresh deploy reports {@code
     * 0.0} and every coin amount would resolve to the cheapest price point — selling any number
     * of coins for $0.99. Below this, setting an offer requires an explicit {@code usd_price}.
     */
    static final double MIN_WEIGHTED_EVENTS = 50;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CoinOfferHandler() {
    }

    /** {@code GET /iap/offer} — public, {@code Access-Control-Allow-Origin: *}, same posture as {@code GET /price}. */
    static void serveOffer(ChannelHandlerContext ctx, AicoinLedger ledger) {
        ledger.getOffer(offerJson -> {
            if (!offerJson.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load offer", true);
                return;
            }
            // An unset offer is "nothing is on sale right now", not an error — apps render their
            // paywall's empty state from it rather than treating it as a failed fetch.
            String offer = offerJson.get();
            sendJson(ctx, "{\"offer\":" + (offer.isEmpty() ? "null" : offer) + "}", true);
        });
    }

    /**
     * {@code POST /iap/offer/check} — the pre-purchase re-check. Returns the offer as it stands
     * <em>at this instant</em> plus an {@code offer_id} pinning that coin amount for {@link
     * #PIN_TTL_SECONDS}, so a purchase the user starts against what they were shown credits that
     * amount even if the operator changes the offer mid-flight.
     *
     * <p>Public and unauthenticated, like the rest of the buy path: a pin is only a promise to
     * credit N coins in exchange for a genuine, Apple-signed purchase of a specific product, so
     * minting one grants nothing on its own. Hoarding is bounded by the TTL.
     */
    static void serveCheck(ChannelHandlerContext ctx, AicoinLedger ledger) {
        ledger.getOffer(offerJson -> {
            if (!offerJson.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load offer", true);
                return;
            }
            if (offerJson.get().isEmpty()) {
                // Nothing on sale: no pin to mint. The app must not open the purchase sheet.
                sendJson(ctx, "{\"offer\":null}", true);
                return;
            }
            String offerId = newOfferId();
            ledger.putOfferPin(offerId, offerJson.get(), PIN_TTL_SECONDS, ok -> {
                if (!ok) {
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not pin offer", true);
                    return;
                }
                sendJson(ctx, "{\"offer\":" + offerJson.get()
                        + ",\"offer_id\":\"" + offerId + "\""
                        + ",\"expires_in\":" + PIN_TTL_SECONDS + "}", true);
            });
        });
    }

    /**
     * {@code POST /admin/iap/offer} — sets how many coins every app sells right now. Body:
     * {@code {"coins":N}} to price N against the live signal, {@code {"coins":N,"usd_price":P}}
     * to pin it to a specific price point instead, or {@code {"coins":0}} to close sales.
     */
    static void serveAdminSet(ChannelHandlerContext ctx, FullHttpRequest request, AicoinLedger ledger, ProxyConfig config) {
        if (!isAuthorized(request, config, ctx)) {
            return;
        }
        Object parsed;
        try {
            parsed = new Yaml().load(new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8));
        } catch (Exception e) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "invalid JSON body", false);
            return;
        }
        if (!(parsed instanceof Map)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "expected a JSON object", false);
            return;
        }
        Map<?, ?> map = (Map<?, ?>) parsed;
        Object coinsRaw = map.get("coins");
        if (!(coinsRaw instanceof Number) || !isNonNegativeInteger((Number) coinsRaw)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "coins must be a non-negative integer", false);
            return;
        }
        int coins = ((Number) coinsRaw).intValue();
        if (coins == 0) {
            // Closing sales: store the empty string so GET /iap/offer reports offer:null. Pins
            // already issued stay valid — someone mid-purchase when sales closed still gets what
            // they were shown, which is the whole point of pinning.
            ledger.setOffer("", ok -> {
                if (!ok) {
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not update offer", false);
                    return;
                }
                sendJson(ctx, "{\"offer\":null}", false);
            });
            return;
        }
        Object usdPriceRaw = map.get("usd_price");
        if (usdPriceRaw != null && (!(usdPriceRaw instanceof Number) || ((Number) usdPriceRaw).doubleValue() <= 0)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "usd_price must be a positive number", false);
            return;
        }
        Double explicitPrice = usdPriceRaw instanceof Number ? ((Number) usdPriceRaw).doubleValue() : null;

        String seedJson = IapPackages.seedJson(config.getIapPackages());
        ledger.getIapPackages(seedJson, packagesJson -> {
            if (!packagesJson.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not load iap packages", false);
                return;
            }
            if (explicitPrice != null) {
                Optional<CoinOffer.Resolved> resolved =
                        CoinOffer.resolveAtPrice(packagesJson.get(), coins, explicitPrice);
                storeResolved(ctx, ledger, resolved, coins);
                return;
            }
            ledger.computePrice(config.getDecayHalflifeDays(), price -> {
                if (price == null) {
                    sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not compute price", false);
                    return;
                }
                if (price.getPriceUsd() <= 0 || price.getWeightedTotal() < MIN_WEIGHTED_EVENTS) {
                    // Refusing rather than guessing: see MIN_WEIGHTED_EVENTS. The operator can
                    // still open sales deliberately by naming the price point themselves.
                    sendError(ctx, HttpResponseStatus.CONFLICT,
                            "price signal too thin to price an offer (price_usd=" + price.getPriceUsd()
                                    + ", weighted_total=" + price.getWeightedTotal()
                                    + ", need >= " + MIN_WEIGHTED_EVENTS + ") — pass an explicit usd_price",
                            false);
                    return;
                }
                Optional<CoinOffer.Resolved> resolved =
                        CoinOffer.resolveByPrice(packagesJson.get(), coins, price.getPriceUsd());
                storeResolved(ctx, ledger, resolved, coins);
            });
        });
    }

    private static void storeResolved(ChannelHandlerContext ctx, AicoinLedger ledger,
            Optional<CoinOffer.Resolved> resolved, int coins) {
        if (!resolved.isPresent()) {
            // No price point covers what these coins are worth. Clamping to the most expensive
            // one would sell the excess for nothing, so this is an error the operator must see.
            sendError(ctx, HttpResponseStatus.CONFLICT,
                    "no price point covers " + coins + " coins — lower the amount or add a higher-priced product",
                    false);
            return;
        }
        String offerJson = CoinOffer.toJson(resolved.get(), Instant.now().toEpochMilli());
        ledger.setOffer(offerJson, ok -> {
            if (!ok) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not update offer", false);
                return;
            }
            sendJson(ctx, "{\"offer\":" + offerJson + "}", false);
        });
    }

    /** 128 bits of {@link SecureRandom}, hex — opaque and unguessable, so a pin can't be forged by naming someone else's id. */
    private static String newOfferId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder("o_");
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isNonNegativeInteger(Number n) {
        double d = n.doubleValue();
        return d >= 0 && d == Math.rint(d) && d <= Integer.MAX_VALUE;
    }

    /** @return true if authorized; on false, has already written the 401/503 — same posture as {@link IapPackagesHandler}. */
    private static boolean isAuthorized(FullHttpRequest request, ProxyConfig config, ChannelHandlerContext ctx) {
        String configuredToken = config.getAdminToken();
        if (configuredToken == null || configuredToken.isEmpty()) {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "admin disabled", false);
            return false;
        }
        String providedToken = request.headers().get(ADMIN_TOKEN_HEADER);
        if (providedToken == null || !AdminHandler.constantTimeEquals(configuredToken, providedToken)) {
            sendError(ctx, HttpResponseStatus.UNAUTHORIZED, "missing or invalid X-Admin-Token", false);
            return false;
        }
        return true;
    }

    private static void sendJson(ChannelHandlerContext ctx, String json, boolean cors) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        if (cors) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message, boolean cors) {
        byte[] bytes = ("{\"error\":\"" + message.replace("\"", "'") + "\"}").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        if (cors) {
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
