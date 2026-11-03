package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The App Store storefront a request comes from, and the providers that storefront may not be
 * routed to — per CONTRACT.md's "Storefront restrictions".
 *
 * <p>Apps send {@code X-AICoin-Storefront: <ISO 3166 alpha-3>} (StoreKit's
 * {@code Storefront.countryCode}) on every request. Apple requires that AI services without a
 * Chinese permit — OpenAI's, all of them — are not offered on the China mainland store, and the
 * proxy, not the app, picks the provider for most calls: automatic routing, panels, editors,
 * escalation, failover, image and speech. So the exclusion lives here, where the choice is made.
 *
 * <p>A request with no header is unrestricted. A restricted provider is never chosen; a request
 * that names one explicitly is refused with {@code 451 provider_unavailable_in_region}, before
 * any coin is held.
 */
final class Storefront {

    private static final Logger LOG = Logger.getLogger(Storefront.class.getName());

    /** Netty has no constant for it: RFC 7725's Unavailable For Legal Reasons. */
    static final HttpResponseStatus UNAVAILABLE_IN_REGION =
            HttpResponseStatus.valueOf(451, "Unavailable For Legal Reasons");

    static final String HEADER = "X-AICoin-Storefront";
    static final String ERROR_CODE = "provider_unavailable_in_region";

    /** What is restricted where when config says nothing: OpenAI, on the China mainland store. */
    static final Map<String, Set<String>> DEFAULT_RESTRICTIONS = Map.of("CHN", Set.of("openai"));

    /** No header, or a storefront with nothing restricted: every provider allowed. */
    static final Storefront NONE = new Storefront(null, Set.of());

    static final AttributeKey<Storefront> KEY = AttributeKey.valueOf("aicoin.storefront");

    private final String code;
    private final Set<String> restricted;

    Storefront(String code, Set<String> restricted) {
        this.code = code;
        this.restricted = Set.copyOf(restricted);
    }

    /**
     * The storefront a header names, with the providers {@code restrictions} withholds from it. A
     * missing, blank or malformed header is {@link #NONE}: the header is optional, and an app that
     * has not learnt its storefront yet is served as before.
     */
    static Storefront fromHeader(String header, Map<String, Set<String>> restrictions) {
        String code = normalize(header);
        if (code == null) {
            return NONE;
        }
        Set<String> restricted = restrictions == null ? null : restrictions.get(code);
        return new Storefront(code, restricted == null ? Set.of() : restricted);
    }

    static Storefront fromHeader(String header, ProxyConfig config) {
        return fromHeader(header, config == null ? DEFAULT_RESTRICTIONS : config.getStorefrontRestrictions());
    }

    /** The storefront codes {@code config} withholds at least one provider from. */
    static Set<String> restrictingCodes(ProxyConfig config) {
        Set<String> codes = new LinkedHashSet<>();
        config.getStorefrontRestrictions().forEach((code, providers) -> {
            if (!providers.isEmpty()) {
                codes.add(code);
            }
        });
        return Set.copyOf(codes);
    }

    /** Upper-cased three letters, or null for anything else. */
    static String normalize(String header) {
        if (header == null) {
            return null;
        }
        String code = header.trim().toUpperCase(Locale.ROOT);
        return code.matches("[A-Z]{3}") ? code : null;
    }

    /**
     * Parses one storefront's restricted list: comma-separated provider names, case-insensitive.
     * {@code none} (or empty) is nothing restricted — the way an env var says "deliberately empty".
     */
    static Set<String> parseList(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null || value.trim().equalsIgnoreCase("none")) {
            return out;
        }
        for (String part : value.split(",")) {
            String provider = part.trim().toLowerCase(Locale.ROOT);
            if (!provider.isEmpty()) {
                out.add(provider);
            }
        }
        return out;
    }

    /** {@code storefronts.restricted} as read from YAML (a map of code to list or comma string). */
    static Map<String, Set<String>> parseYaml(Object node) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (!(node instanceof Map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
            String code = normalize(String.valueOf(entry.getKey()));
            if (code == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Collection) {
                List<String> parts = new ArrayList<>();
                for (Object v : (Collection<?>) value) {
                    parts.add(String.valueOf(v));
                }
                out.put(code, parseList(String.join(",", parts)));
            } else {
                out.put(code, parseList(value == null ? null : String.valueOf(value)));
            }
        }
        return out;
    }

    /** The storefront of the request currently being served on {@code channel}, or {@link #NONE}. */
    static Storefront of(Channel channel) {
        if (channel == null) {
            return NONE;
        }
        Storefront storefront = channel.attr(KEY).get();
        return storefront == null ? NONE : storefront;
    }

    static void set(Channel channel, Storefront storefront) {
        channel.attr(KEY).set(storefront);
    }

    /** The alpha-3 code the request sent, or null without one. */
    String code() {
        return code;
    }

    /** Whether anything at all is withheld from this storefront. */
    boolean isRestricted() {
        return !restricted.isEmpty();
    }

    boolean allows(String provider) {
        return provider == null || !restricted.contains(provider.trim().toLowerCase(Locale.ROOT));
    }

    /** {@code providers} without the restricted ones, order kept: the next best is next. */
    List<String> filter(List<String> providers) {
        if (restricted.isEmpty()) {
            return providers;
        }
        List<String> kept = new ArrayList<>(providers.size());
        for (String provider : providers) {
            if (allows(provider)) {
                kept.add(provider);
            }
        }
        return kept;
    }

    /** The {@code 451} body for a request that named {@code provider} explicitly. */
    String errorJson(String provider) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", ERROR_CODE);
        m.put("message", provider + " is not available on the " + code + " App Store storefront");
        m.put("provider", provider);
        m.put("storefront", code);
        return Json.write(m);
    }

    /**
     * Refuses a request that named a provider this storefront may not use: {@code 451} with
     * {@link #errorJson}, nothing held or charged. Logged, so a refusal is traceable beside the
     * access-log line that carries the same storefront.
     */
    void refuse(ChannelHandlerContext ctx, String provider, String what) {
        LOG.info("storefront " + code + ": refused " + what + " naming " + provider);
        byte[] bytes = errorJson(provider).getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, UNAVAILABLE_IN_REGION, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
