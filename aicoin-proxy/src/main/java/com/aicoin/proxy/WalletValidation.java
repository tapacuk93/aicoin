package com.aicoin.proxy;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-function pieces of wallet-id-as-API-key auth, per CONTRACT.md's
 * "Auth — wallet id IS the API key, gated on a positive balance" section:
 * the caller's aicoin wallet id doubles as their API key, sent as the
 * required {@code X-Api-Key} header (replacing the old {@code X-User-Id}),
 * and is only allowed through when the wallet's reported balance is
 * strictly positive.
 *
 * This class holds only the decision logic — header extraction, the
 * balance-check URL to call, and what the *result* of that call (status
 * code + body) means — with no network I/O of its own, so it can be
 * unit-tested without a live Netty server. The actual async HTTP GET lives
 * in {@link WalletValidator}.
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
     * actually received — reports whether the aicoin node itself was
     * reachable (a prerequisite for the balance gate in {@link #decide}).
     *
     * <p>Per CONTRACT.md, <em>any</em> 2xx response counts as reachable,
     * regardless of the balance value reported (the balance value is what
     * the separate gate in {@link #decide} is for); anything else, including
     * no response at all, is not reachable.
     */
    public static boolean isReachable(Optional<Integer> balanceCheckStatusCode) {
        return balanceCheckStatusCode.isPresent()
                && balanceCheckStatusCode.get() >= 200
                && balanceCheckStatusCode.get() < 300;
    }

    /**
     * Parses the {@code balance} numeric field out of the JSON body of a
     * {@code GET {balanceUrlBase}/balance/{walletId}} response, per
     * CONTRACT.md's {@code GET /balance/{user_id}} shape
     * ({@code {"user_id":"...","balance":N}}). Parsed the same way as
     * {@link CostCalculator} parses upstream usage bodies: SnakeYAML, since
     * valid JSON is valid YAML for this simple object shape.
     *
     * @return the balance as a {@link Number} (preserving its original
     *     int/long/double shape for faithful re-serialization in a {@code
     *     402} body), or {@link Optional#empty()} if the body is missing,
     *     unparseable, or has no numeric {@code balance} field.
     */
    public static Optional<Number> parseBalance(String jsonBody) {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            return Optional.empty();
        }
        Object parsed;
        try {
            parsed = new Yaml().load(jsonBody);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map)) {
            return Optional.empty();
        }
        Object balance = ((Map<?, ?>) parsed).get("balance");
        return (balance instanceof Number) ? Optional.of((Number) balance) : Optional.empty();
    }

    /**
     * Combines the reachability check ({@link #isReachable}) with the
     * balance value parsed from a successful call's body ({@link
     * #parseBalance}) into the single decision {@link ProxyFrontendHandler}
     * needs to make, per CONTRACT.md's "Auth — wallet id IS the API key,
     * gated on a positive balance" section:
     *
     * <ul>
     *   <li>Not reachable, or reachable but with no parseable numeric
     *       {@code balance} field (nothing to gate on — treated the same as
     *       "could not validate wallet") -&gt; {@link
     *       BalanceDecision#isUnreachable()} true -&gt; caller responds
     *       {@code 503 {"error":"could not validate wallet"}}.</li>
     *   <li>Reachable with {@code balance > 0} -&gt; {@link
     *       BalanceDecision#shouldProceed()} true -&gt; caller forwards to
     *       the AI provider exactly as before.</li>
     *   <li>Reachable with {@code balance <= 0} (zero or negative — negative
     *       shouldn't normally occur, but is treated the same defensively)
     *       -&gt; {@link BalanceDecision#hasInsufficientBalance()} true
     *       -&gt; caller responds {@code 402
     *       {"error":"insufficient aicoin balance","balance":<value>}}.</li>
     * </ul>
     */
    public static BalanceDecision decide(Optional<Integer> balanceCheckStatusCode, Optional<Number> balance) {
        if (!isReachable(balanceCheckStatusCode) || !balance.isPresent()) {
            return BalanceDecision.unreachable();
        }
        return BalanceDecision.reachable(balance.get());
    }

    /** Outcome of {@link #decide}: unreachable (-&gt; 503), insufficient balance (-&gt; 402), or proceed. */
    public static final class BalanceDecision {
        private final boolean reachable;
        private final Number balance;

        private BalanceDecision(boolean reachable, Number balance) {
            this.reachable = reachable;
            this.balance = balance;
        }

        static BalanceDecision unreachable() {
            return new BalanceDecision(false, null);
        }

        static BalanceDecision reachable(Number balance) {
            return new BalanceDecision(true, balance);
        }

        /** True when the aicoin node itself couldn't be validated (caller responds 503). */
        public boolean isUnreachable() {
            return !reachable;
        }

        /** True when the wallet was validated and its balance is strictly positive. */
        public boolean shouldProceed() {
            return reachable && balance.doubleValue() > 0;
        }

        /** True when the wallet was validated but its balance is zero or negative (caller responds 402). */
        public boolean hasInsufficientBalance() {
            return reachable && !shouldProceed();
        }

        /** The wallet's reported balance; only meaningful when {@link #isUnreachable()} is false. */
        public Number getBalance() {
            return balance;
        }
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
