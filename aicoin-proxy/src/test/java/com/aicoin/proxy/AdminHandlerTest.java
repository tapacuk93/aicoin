package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdminHandlerTest {

    @Test
    void creditBodyFieldsAreReadOutOfTheJson() {
        String body = "{\"address\":\"" + "a".repeat(64) + "\",\"amount\":1000,\"reason\":\"dev top-up\"}";
        assertEquals("a".repeat(64), AdminHandler.stringField(body, "address"));
        assertEquals("dev top-up", AdminHandler.stringField(body, "reason"));
        assertNull(AdminHandler.stringField(body, "reference"), "an absent field reads as absent");
    }

    @Test
    void aCreditIsCappedSoAMistypedZeroIsNotAPolicy() {
        // Not a limit on how many coins may exist — the operator can call it again — but the
        // difference between 1,000 and 10,000,000 should not be one keystroke.
        assertTrue(AdminHandler.MAX_CREDIT_AICOIN >= 1_000);
        assertTrue(AdminHandler.MAX_CREDIT_AICOIN <= 10_000_000);
    }

    @Test
    void isValidAddressAcceptsExactly64LowercaseHexChars() {
        assertTrue(AdminHandler.isValidAddress("a".repeat(64)));
        assertTrue(AdminHandler.isValidAddress("0123456789abcdef".repeat(4)));
    }

    @Test
    void isValidAddressAcceptsUppercaseHexToo() {
        assertTrue(AdminHandler.isValidAddress("ABCDEF0123456789".repeat(4)));
    }

    @Test
    void isValidAddressRejectsWrongLength() {
        assertFalse(AdminHandler.isValidAddress("abc"));
        assertFalse(AdminHandler.isValidAddress("a".repeat(65)));
        assertFalse(AdminHandler.isValidAddress(""));
    }

    @Test
    void isValidAddressRejectsNonHexCharacters() {
        assertFalse(AdminHandler.isValidAddress("z".repeat(64)));
        assertFalse(AdminHandler.isValidAddress("../../etc/passwd".repeat(4).substring(0, 64)));
    }

    @Test
    void constantTimeEqualsMatchesOnlyIdenticalStrings() {
        assertTrue(AdminHandler.constantTimeEquals("s3cret", "s3cret"));
        assertFalse(AdminHandler.constantTimeEquals("s3cret", "wrong"));
        assertFalse(AdminHandler.constantTimeEquals("s3cret", "s3cre"));
        assertFalse(AdminHandler.constantTimeEquals("", "s3cret"));
    }
}
