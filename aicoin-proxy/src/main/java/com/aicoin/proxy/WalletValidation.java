package com.aicoin.proxy;

import java.util.Optional;

/**
 * Pure-function pieces of wallet-id-as-API-key auth, per CONTRACT.md's
 * "Auth — wallet id IS the API key, gated on a positive balance" section:
 * the caller's aicoin wallet id doubles as their API key, sent as the
 * required {@code X-Api-Key} header, and is only allowed through when the
 * wallet's reported balance is strictly positive.
 *
 * <p>This class holds only the decision logic — header extraction and what
 * a balance lookup's *result* means — with no I/O of its own, so it can be
 * unit-tested without a live Redis connection. The actual balance lookup
 * lives in {@link AicoinLedger}.
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
     * Given the outcome of a ledger balance lookup — {@link Optional#empty()}
     * meaning the lookup itself failed (Redis unreachable/timed out), or a
     * present value meaning a balance was actually read — decides what
     * {@link ProxyFrontendHandler} should do next, per CONTRACT.md's "Auth —
     * wallet id IS the API key, gated on a positive balance" section:
     *
     * <ul>
     *   <li>Lookup failed -&gt; {@link BalanceDecision#isUnreachable()} true
     *       -&gt; caller responds {@code 503 {"error":"could not validate
     *       wallet"}}.</li>
     *   <li>{@code balance > 0} -&gt; {@link BalanceDecision#shouldProceed()}
     *       true -&gt; caller forwards to the AI provider exactly as
     *       before.</li>
     *   <li>{@code balance <= 0} (zero or negative — negative shouldn't
     *       normally occur, but is treated the same defensively) -&gt;
     *       {@link BalanceDecision#hasInsufficientBalance()} true -&gt;
     *       caller responds {@code 402 {"error":"insufficient aicoin
     *       balance","balance":<value>}}.</li>
     * </ul>
     */
    public static BalanceDecision decide(Optional<Double> balance) {
        if (!balance.isPresent()) {
            return BalanceDecision.unreachable();
        }
        return BalanceDecision.reachable(balance.get());
    }

    /** Outcome of {@link #decide}: unreachable (-&gt; 503), insufficient balance (-&gt; 402), or proceed. */
    public static final class BalanceDecision {
        private final boolean reachable;
        private final Double balance;

        private BalanceDecision(boolean reachable, Double balance) {
            this.reachable = reachable;
            this.balance = balance;
        }

        static BalanceDecision unreachable() {
            return new BalanceDecision(false, null);
        }

        static BalanceDecision reachable(double balance) {
            return new BalanceDecision(true, balance);
        }

        /** True when the ledger itself couldn't be reached (caller responds 503). */
        public boolean isUnreachable() {
            return !reachable;
        }

        /** True when the wallet was validated and its balance is strictly positive. */
        public boolean shouldProceed() {
            return reachable && balance > 0;
        }

        /** True when the wallet was validated but its balance is zero or negative (caller responds 402). */
        public boolean hasInsufficientBalance() {
            return reachable && !shouldProceed();
        }

        /** The wallet's reported balance; only meaningful when {@link #isUnreachable()} is false. */
        public Double getBalance() {
            return balance;
        }
    }
}

