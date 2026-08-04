package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-function wallet-id-as-API-key auth logic, per CONTRACT.md's "Auth —
 * wallet id IS the API key, gated on a positive balance" section: header
 * extraction and the balance-gating decision — independent of any live
 * Redis connection (the actual balance lookup lives in {@link AicoinLedger}).
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

    // --- decide: the balance gate, per CONTRACT.md's "Auth — wallet id IS
    // the API key, gated on a positive balance" section ---

    @Test
    void positiveBalanceProceeds() {
        WalletValidation.BalanceDecision decision = WalletValidation.decide(Optional.of(3.0));
        assertTrue(decision.shouldProceed());
        assertFalse(decision.hasInsufficientBalance());
        assertFalse(decision.isUnreachable());
        assertEquals(3.0, decision.getBalance());
    }

    @Test
    void fractionalPositiveBalanceProceeds() {
        WalletValidation.BalanceDecision decision = WalletValidation.decide(Optional.of(0.1));
        assertTrue(decision.shouldProceed());
    }

    @Test
    void zeroBalanceIsInsufficient() {
        WalletValidation.BalanceDecision decision = WalletValidation.decide(Optional.of(0.0));
        assertFalse(decision.shouldProceed());
        assertTrue(decision.hasInsufficientBalance());
        assertFalse(decision.isUnreachable());
        assertEquals(0.0, decision.getBalance());
    }

    @Test
    void negativeBalanceIsInsufficient() {
        // Shouldn't normally occur (transfers can't overdraw a wallet), but
        // treated the same as <= 0 defensively per CONTRACT.md.
        WalletValidation.BalanceDecision decision = WalletValidation.decide(Optional.of(-5.0));
        assertFalse(decision.shouldProceed());
        assertTrue(decision.hasInsufficientBalance());
        assertFalse(decision.isUnreachable());
        assertEquals(-5.0, decision.getBalance());
    }

    @Test
    void unreachableLedgerIsNeitherProceedNorInsufficientBalance() {
        WalletValidation.BalanceDecision decision = WalletValidation.decide(Optional.empty());
        assertTrue(decision.isUnreachable());
        assertFalse(decision.shouldProceed());
        assertFalse(decision.hasInsufficientBalance());
    }
}
