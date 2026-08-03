package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-function wallet-id-as-API-key auth logic, per CONTRACT.md's "Auth —
 * wallet id IS the API key" section: header extraction, balance-check URL
 * construction, and the success/failure/timeout decision — all independent
 * of any live Netty server (the actual network call lives in {@link
 * WalletValidator}).
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
}
