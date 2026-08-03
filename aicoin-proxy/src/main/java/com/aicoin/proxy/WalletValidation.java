package com.aicoin.proxy;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Optional;

/**
 * Pure-function pieces of wallet-id-as-API-key auth, per CONTRACT.md's
 * "Auth — wallet id IS the API key" section: the caller's aicoin wallet id
 * doubles as their API key, sent as the required {@code X-Api-Key} header
 * (replacing the old {@code X-User-Id}).
 *
 * This class holds only the decision logic — header extraction, the
 * balance-check URL to call, and what the *result* of that call means —
 * with no network I/O of its own, so it can be unit-tested without a live
 * Netty server. The actual async HTTP GET lives in {@link WalletValidator}.
 */
public final class WalletValidation {

    static final String HEADER_NAME = "X-Api-Key";

    private WalletValidation() {
    }

    /**
     * Extracts the wallet id from the raw {@code X-Api-Key} header value.
     * A missing, null, or blank/whitespace-only header resolves to {@link
     * Optional#empty()}, which callers must turn into
     * {@code 401 {"error":"missing X-Api-Key (wallet id)"}}.
     */
    public static Optional<String> extractWalletId(String xApiKeyHeaderValue) {
        if (xApiKeyHeaderValue == null) {
            return Optional.empty();
        }
        String trimmed = xApiKeyHeaderValue.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    /**
     * Builds the wallet-validation URL {@code {balanceUrlBase}/balance/{walletId}}
     * per CONTRACT.md, URL-path-encoding the wallet id segment and tolerating
     * a trailing slash on {@code balanceUrlBase}.
     */
    public static String balanceUrl(String balanceUrlBase, String walletId) {
        String base = balanceUrlBase != null && balanceUrlBase.endsWith("/")
                ? balanceUrlBase.substring(0, balanceUrlBase.length() - 1)
                : balanceUrlBase;
        return base + "/balance/" + encodePathSegment(walletId);
    }

    /**
     * Given the outcome of the {@code GET {balanceUrlBase}/balance/{walletId}}
     * call — {@link Optional#empty()} meaning the call itself failed to
     * complete (connect failure, write failure, or timeout — the aicoin node
     * was unreachable), or a present value meaning that HTTP status code was
     * actually received — decides whether the proxy should proceed with
     * forwarding to the AI provider.
     *
     * <p>Per CONTRACT.md, <em>any</em> 2xx response is a pass, regardless of
     * the balance value reported (this is a reachability check on the aicoin
     * node, not an identity check); anything else, including no response at
     * all, means {@code 503 {"error":"could not validate wallet"}}.
     */
    public static boolean isReachable(Optional<Integer> balanceCheckStatusCode) {
        return balanceCheckStatusCode.isPresent()
                && balanceCheckStatusCode.get() >= 200
                && balanceCheckStatusCode.get() < 300;
    }

    private static String encodePathSegment(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is always supported; unreachable in practice.
            return s;
        }
    }
}
