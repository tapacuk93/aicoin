package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a forwarded request targets a <em>free</em> upstream
 * endpoint — one the real provider does not bill the proxy for (listing
 * models, listing ElevenLabs voices, Anthropic's {@code count_tokens},
 * Google's {@code :countTokens}, account/balance lookups) — as opposed to a
 * paid inference call.
 *
 * <p>This is the target-side half of CONTRACT.md's paid-vs-free boundary.
 * The other half is outcome-side (a non-2xx call is refunded, a faucet claim
 * is not a call at all). A free target is never debited, is never refunded
 * (there was no debit), and never feeds the price formula — its upstream
 * cost really is zero, so recording {@code defaultCostUsdPerCall} for it
 * would inflate {@code GET /price} with spend that never happened. It still
 * requires a valid API token and still gets the proxy's own paid key
 * injected; it just doesn't cost the wallet a coin, so an app can list
 * voices or models with an empty balance.
 *
 * <p><b>Pattern syntax</b> ({@code providers.<name>.freePaths} entries):
 * an optional HTTP method, a space, then a path glob where {@code *} matches
 * any run of characters (including {@code /}):
 *
 * <pre>
 *   GET /v1/models              exact path, GET only
 *   GET /v1/models/*            any path under /v1/models
 *   POST /v1beta/models/*:countTokens
 *   /v1/voices                  any method
 * </pre>
 *
 * Methods are compared case-insensitively; paths case-sensitively, since
 * provider paths are. The query string is not part of the match — callers
 * pass the path only.
 *
 * <p><b>Fails closed.</b> A path containing a percent-escape or a {@code .}/
 * {@code ..} segment is never free, whatever the patterns say: upstreams
 * normalize such paths, so {@code /v1/models/../chat/completions} would
 * otherwise match a free pattern here and still reach a billed endpoint
 * there. Treating those as paid costs a coin in the (vanishingly rare)
 * legitimate case and closes the free-ride hole in the adversarial one.
 */
public final class FreeTargets {

    private FreeTargets() {
    }

    /**
     * @param method HTTP method of the inbound request, e.g. {@code "POST"}
     * @param path   request path with no query string (see {@code ProxyFrontendHandler.pathOnly})
     * @param patterns this provider's {@code freePaths}, in the syntax documented above
     * @return true when the call must be forwarded without debiting a coin or recording a price event
     */
    public static boolean isFree(String method, String path, List<String> patterns) {
        if (method == null || path == null || patterns == null || patterns.isEmpty()) {
            return false;
        }
        if (!isNormalized(path)) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(method, path, pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the path is already in the form the upstream will route on:
     * no percent-escapes (which could hide {@code /} or {@code .}) and no
     * {@code .}/{@code ..} segments (which upstream normalization would
     * collapse into a different, possibly billed, endpoint).
     */
    private static boolean isNormalized(String path) {
        if (path.indexOf('%') >= 0) {
            return false;
        }
        int start = 0;
        while (start <= path.length()) {
            int slash = path.indexOf('/', start);
            int end = slash >= 0 ? slash : path.length();
            String segment = path.substring(start, end);
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return true;
    }

    private static boolean matches(String method, String path, String pattern) {
        if (pattern == null) {
            return false;
        }
        String trimmed = pattern.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        String pathGlob = trimmed;
        int space = trimmed.indexOf(' ');
        if (space > 0) {
            String patternMethod = trimmed.substring(0, space).trim();
            pathGlob = trimmed.substring(space + 1).trim();
            if (!"*".equals(patternMethod) && !patternMethod.equalsIgnoreCase(method)) {
                return false;
            }
        }
        return globMatches(pathGlob, path);
    }

    /** Iterative glob match for a pattern whose only metacharacter is {@code *} (matching any run of characters). */
    private static boolean globMatches(String glob, String value) {
        int g = 0;
        int v = 0;
        int starIdx = -1;
        int matchIdx = 0;
        while (v < value.length()) {
            if (g < glob.length() && (glob.charAt(g) == value.charAt(v))) {
                g++;
                v++;
            } else if (g < glob.length() && glob.charAt(g) == '*') {
                starIdx = g;
                matchIdx = v;
                g++;
            } else if (starIdx >= 0) {
                g = starIdx + 1;
                matchIdx++;
                v = matchIdx;
            } else {
                return false;
            }
        }
        while (g < glob.length() && glob.charAt(g) == '*') {
            g++;
        }
        return g == glob.length();
    }

    /**
     * Parses a comma-separated {@code AICOIN_PROXY_<PROVIDER>_FREEPATHS} env
     * value into pattern entries. The literal value {@code none} (any case)
     * means "no free targets for this provider" — the only way to switch the
     * whole feature off from the environment, since an empty env var is
     * indistinguishable from an unset one everywhere else in
     * {@link ProxyConfig}.
     */
    static List<String> parseEnvList(String value) {
        if (value == null || value.trim().isEmpty() || "none".equalsIgnoreCase(value.trim())) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String entry = part.trim();
            if (!entry.isEmpty()) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /** Normalizes a YAML-parsed {@code freePaths} value (a list of strings) into pattern entries. */
    static List<String> parseYamlList(Object raw) {
        if (!(raw instanceof List)) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Object entry : (List<?>) raw) {
            if (entry == null) {
                continue;
            }
            String s = String.valueOf(entry).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
