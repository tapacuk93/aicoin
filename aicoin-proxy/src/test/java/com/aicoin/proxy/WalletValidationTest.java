package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-function wallet-id-as-API-key auth logic, per CONTRACT.md's "Auth —
 * wallet id IS the API key, gated on a positive balance" section: header
 * extraction, balance-check URL construction, the reachability decision,
 * balance parsing, and the combined balance-gating decision — all
 * independent of any live Netty server (the actual network call lives in
 * {@link WalletValidator}).
 */
class WalletValidationTest {

    // --- extractWalletId: given a request with/without X-Api-Key ---

    @Test
    void extractsWalletIdFromHeaderValue() {
        Optional<String> id = WalletValidation.extractWalletId("wallet-123");
        assertTrue(id.isPresent());
        assertEquals("wallet-123", id.get());
    }

    @Test
    void missingHeaderIsNotExtracted() {
        assertFalse(WalletValidation.extractWalletId(null).isPresent());
    }

    @Test
    void emptyOrBlankHeaderIsNotExtracted() {
        assertFalse(WalletValidation.extractWalletId("").isPresent());
        assertFalse(WalletValidation.extractWalletId("   ").isPresent());
    }

    @Test
    void headerValueIsTrimmed() {
        Optional<String> id = WalletValidation.extractWalletId("  wallet-abc  ");
        assertTrue(id.isPresent());
        assertEquals("wallet-abc", id.get());
    }

    // --- balanceUrl: validation-request-construction ---

    @Test
    void buildsBalanceUrlFromBaseAndWalletId() {
        assertEquals("http://localhost:9944/balance/wallet-123",
                WalletValidation.balanceUrl("http://localhost:9944", "wallet-123"));
    }

    @Test
    void buildsBalanceUrlTolerantOfTrailingSlashOnBase() {
        assertEquals("http://localhost:9944/balance/wallet-123",
                WalletValidation.balanceUrl("http://localhost:9944/", "wallet-123"));
    }

    @Test
    void balanceUrlEncodesSpecialCharactersInWalletId() {
        assertEquals("http://localhost:9944/balance/wallet%2Fwith%2Fslashes",
                WalletValidation.balanceUrl("http://localhost:9944", "wallet/with/slashes"));
        assertEquals("http://localhost:9944/balance/wallet%20with%20space",
                WalletValidation.balanceUrl("http://localhost:9944", "wallet with space"));
    }

    // --- isReachable: given the aicoin node's HTTP response (success/failure/timeout) ---

    @Test
    void any2xxStatusIsReachable() {
        assertTrue(WalletValidation.isReachable(Optional.of(200)));
        assertTrue(WalletValidation.isReachable(Optional.of(201)));
        assertTrue(WalletValidation.isReachable(Optional.of(204)));
        assertTrue(WalletValidation.isReachable(Optional.of(299)));
    }

    @Test
    void non2xxStatusIsNotReachable() {
        assertFalse(WalletValidation.isReachable(Optional.of(300)));
        assertFalse(WalletValidation.isReachable(Optional.of(404)));
        assertFalse(WalletValidation.isReachable(Optional.of(500)));
        assertFalse(WalletValidation.isReachable(Optional.of(199)));
    }

    @Test
    void noResponseAtAllMeansConnectFailureOrTimeoutAndIsNotReachable() {
        // Optional.empty() models a call that never completed at all —
        // connect failure, write failure, or read timeout — per
        // CONTRACT.md's "fails or times out" -> 503 rule.
        assertFalse(WalletValidation.isReachable(Optional.empty()));
    }

    // --- parseBalance: extracting the "balance" numeric field from a GET /balance/{walletId} body ---

    @Test
    void parsesPositiveBalanceFromResponseBody() {
        Optional<Number> balance = WalletValidation.parseBalance("{\"user_id\":\"w1\",\"balance\":3}");
        assertTrue(balance.isPresent());
        assertEquals(3.0, balance.get().doubleValue(), 1e-12);
    }

    @Test
    void parsesFractionalBalanceFromResponseBody() {
        Optional<Number> balance = WalletValidation.parseBalance("{\"user_id\":\"w1\",\"balance\":0.5}");
        assertTrue(balance.isPresent());
        assertEquals(0.5, balance.get().doubleValue(), 1e-12);
    }

    @Test
    void parsesZeroBalanceFromResponseBody() {
        Optional<Number> balance = WalletValidation.parseBalance("{\"user_id\":\"w1\",\"balance\":0}");
        assertTrue(balance.isPresent());
        assertEquals(0.0, balance.get().doubleValue(), 1e-12);
    }

    @Test
    void parsesNegativeBalanceFromResponseBody() {
        Optional<Number> balance = WalletValidation.parseBalance("{\"user_id\":\"w1\",\"balance\":-2}");
        assertTrue(balance.isPresent());
        assertEquals(-2.0, balance.get().doubleValue(), 1e-12);
    }

    @Test
    void missingBalanceFieldIsNotParsed() {
        assertFalse(WalletValidation.parseBalance("{\"user_id\":\"w1\"}").isPresent());
    }

    @Test
    void unparseableBodyIsNotParsed() {
        assertFalse(WalletValidation.parseBalance("not json at all {{{").isPresent());
    }

    @Test
    void nullOrEmptyBodyIsNotParsed() {
        assertFalse(WalletValidation.parseBalance(null).isPresent());
        assertFalse(WalletValidation.parseBalance("").isPresent());
        assertFalse(WalletValidation.parseBalance("   ").isPresent());
    }

    // --- decide: the combined reachability + balance gate, per CONTRACT.md's
    // "Auth — wallet id IS the API key, gated on a positive balance" section ---

    @Test
    void positiveBalanceOnReachableNodeProceeds() {
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.of(200), Optional.of(3));
        assertTrue(decision.shouldProceed());
        assertFalse(decision.hasInsufficientBalance());
        assertFalse(decision.isUnreachable());
        assertEquals(3, decision.getBalance());
    }

    @Test
    void fractionalPositiveBalanceProceeds() {
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.of(200), Optional.of(0.1));
        assertTrue(decision.shouldProceed());
    }

    @Test
    void zeroBalanceOnReachableNodeIsInsufficient() {
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.of(200), Optional.of(0));
        assertFalse(decision.shouldProceed());
        assertTrue(decision.hasInsufficientBalance());
        assertFalse(decision.isUnreachable());
        assertEquals(0, decision.getBalance());
    }

    @Test
    void negativeBalanceOnReachableNodeIsInsufficient() {
        // Shouldn't normally occur (transfers can't overdraw a wallet), but
        // treated the same as <= 0 defensively per CONTRACT.md.
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.of(200), Optional.of(-5));
        assertFalse(decision.shouldProceed());
        assertTrue(decision.hasInsufficientBalance());
        assertFalse(decision.isUnreachable());
        assertEquals(-5, decision.getBalance());
    }

    @Test
    void unreachableNodeIsNeitherProceedNorInsufficientBalance() {
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.empty(), Optional.empty());
        assertTrue(decision.isUnreachable());
        assertFalse(decision.shouldProceed());
        assertFalse(decision.hasInsufficientBalance());
    }

    @Test
    void non2xxStatusIsUnreachableRegardlessOfBalance() {
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.of(500), Optional.of(5));
        assertTrue(decision.isUnreachable());
        assertFalse(decision.shouldProceed());
    }

    @Test
    void reachableButUnparseableBalanceIsTreatedAsUnreachable() {
        // A 2xx response with no numeric "balance" field is nothing to gate
        // on, so it's treated the same as "could not validate wallet".
        WalletValidation.BalanceDecision decision =
                WalletValidation.decide(Optional.of(200), Optional.empty());
        assertTrue(decision.isUnreachable());
        assertFalse(decision.shouldProceed());
        assertFalse(decision.hasInsufficientBalance());
    }
}
