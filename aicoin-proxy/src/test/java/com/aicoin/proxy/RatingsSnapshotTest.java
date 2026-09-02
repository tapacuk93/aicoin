package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The document wallets carry so a rating can be checked with no network. Two implementations sign
 * and verify it, so the bytes have to be exactly agreed — which is what these pin.
 */
class RatingsSnapshotTest {

    private static final long ISSUED = 1_800_000_000_000L;

    @Test
    void theSignedTextIsCanonicalAndDull() {
        String canonical = RatingsSnapshot.canonical(ISSUED, List.<String[]>of(
                new String[] {"a".repeat(64), "5"},
                new String[] {"b".repeat(64), "0"}));

        assertEquals("aicoin-ratings\n" + ISSUED + "\n"
                + "a".repeat(64) + ":5\n"
                + "b".repeat(64) + ":0\n", canonical);
    }

    @Test
    void theTimeItWasMadeIsSignedWithIt() {
        // A snapshot without its age is a rating that claims to be current forever. The timestamp
        // is inside the signed text so it cannot be restated by whoever passes the file on.
        List<String[]> ratings = List.<String[]>of(new String[] {"c".repeat(64), "3"});
        String early = RatingsSnapshot.canonical(ISSUED, ratings);
        String later = RatingsSnapshot.canonical(ISSUED + 1000, ratings);
        assertTrue(!early.equals(later));
    }

    @Test
    void theDocumentCarriesItsOwnLimits() {
        List<String[]> ratings = List.<String[]>of(new String[] {"d".repeat(64), "4"});
        String json = RatingsSnapshot.json(ISSUED, ratings, "ab12");
        assertTrue(json.contains("\"issued_at\":" + ISSUED), json);
        assertTrue(json.contains("\"count\":1"), json);
        // A client has to know whether it is holding everything or the first N of something larger.
        assertTrue(json.contains("\"max\":" + RatingsSnapshot.MAX_WALLETS), json);
        assertTrue(json.contains("\"" + "d".repeat(64) + "\":4"), json);
        assertTrue(json.contains("\"signature\":\"ab12\""), json);
    }

    @Test
    void anEmptySnapshotIsStillAValidDocument() {
        // A ledger nobody has used yet: the answer is an empty list signed at a time, not an error
        // and not a missing file that a client might read as "everybody is fine".
        List<String[]> empty = List.of();
        String json = RatingsSnapshot.json(ISSUED, empty, "cd34");
        assertTrue(json.contains("\"count\":0"), json);
        assertTrue(json.contains("\"ratings\":{}"), json);
    }
}
