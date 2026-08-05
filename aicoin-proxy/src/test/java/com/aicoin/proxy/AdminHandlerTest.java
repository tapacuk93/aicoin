package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AdminHandlerTest {

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
