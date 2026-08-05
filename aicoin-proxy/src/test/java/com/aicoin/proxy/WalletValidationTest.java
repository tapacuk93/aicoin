package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure-function {@code X-Api-Key} header extraction — independent of any
 * live Redis connection. The actual balance gate lives in {@link
 * AicoinLedger#debitForCall}, and live-signature/token verification in
 * {@link WalletSignature}.
 */
class WalletValidationTest {

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
}
