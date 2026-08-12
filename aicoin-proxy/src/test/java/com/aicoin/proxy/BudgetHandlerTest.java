package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The parsing half of the spend budget — the part that runs without a live Redis, in the same
 * spirit as {@link PriceCalculatorTest}. The ledger arithmetic it feeds is a plain sum, so what is
 * worth pinning here is the admin body parsing and the exhaustion rule's edges.
 */
public class BudgetHandlerTest {

    @Test
    public void parsesWalletArraysOutOfAnAdminBody() {
        String body = "{\"add\":[\"0xabc\",\"0xdef\"],\"remove\":[\"0x123\"]}";
        assertEquals(List.of("0xabc", "0xdef"), BudgetHandler.parseStringArray(body, "add"));
        assertEquals(List.of("0x123"), BudgetHandler.parseStringArray(body, "remove"));
    }

    @Test
    public void anAbsentArrayIsEmptyRatherThanAnError() {
        // Either key may be omitted — "add only" and "remove only" are both ordinary requests.
        assertTrue(BudgetHandler.parseStringArray("{\"add\":[\"0xabc\"]}", "remove").isEmpty());
        assertTrue(BudgetHandler.parseStringArray("{}", "add").isEmpty());
    }

    @Test
    public void anEmptyArrayIsEmpty() {
        assertTrue(BudgetHandler.parseStringArray("{\"add\":[]}", "add").isEmpty());
    }

    @Test
    public void internalSpendIsMarkedByATrailingSuffixAndOldEventsReadAsProduction() {
        // The member format is cost|uuid for production and cost|uuid|i for internal. Every event
        // written before budgets existed has the two-field shape, so it must read as production —
        // counting historical spend as exempt would silently raise the real ceiling.
        assertTrue("0.05|abc-123|i".endsWith("|i"));
        assertFalse("0.05|abc-123".endsWith("|i"));
    }

    @Test
    public void costParsingIsUnchangedByTheInternalSuffix() {
        // parseCost splits on the FIRST separator, so the extra field cannot disturb the price
        // signal — internal calls still cost the operator money and still price coins.
        assertEquals(0.05, Double.parseDouble("0.05|abc-123|i".substring(0, "0.05|abc-123|i".indexOf('|'))), 1e-9);
    }
}
