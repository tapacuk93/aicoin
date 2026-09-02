package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The distinction the score exists to make: a wallet with no history is not a wallet with a clean
 * one. Both have done nothing wrong; only one of them has anything to lose.
 */
class ReputationTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Map<String, Long> log(long entries, long calls, long purchases, long ageDays) {
        return log(entries, calls, purchases, ageDays, 0);
    }

    private static Map<String, Long> log(long entries, long calls, long purchases, long ageDays, long counterparties) {
        // Counterparties passed here are the ones in good standing: only those count.
        return Map.of("entries", entries, "calls", calls, "purchases", purchases,
                "counterparties", counterparties, "solid_counterparties", counterparties,
                "first_seen", ageDays == 0 ? 0 : NOW - ageDays * DAY);
    }

    @Test
    void aBrandNewWalletScoresNothing() {
        // The case worth naming: nothing bought, nothing spent, nothing to lose. It has done
        // nothing wrong and is exactly what a wallet made to take one payment and vanish looks like.
        assertEquals(0, Reputation.score(0, 0, log(0, 0, 0, 0), NOW));
        assertTrue(Reputation.reasons(0, 0, log(0, 0, 0, 0), NOW).stream()
                .anyMatch(reason -> reason.contains("never done anything")));
    }

    @Test
    void aWalletThatHasPaidUsedAndLastedScoresFull() {
        assertEquals(5, Reputation.score(100, 0, log(40, 30, 2, 30, 6), NOW));
    }

    @Test
    void aProvenDoubleSpendIsTheAnswerNotADeduction() {
        // However good the rest of the record is: somebody signed the same money over to two
        // people, and nothing else in the log argues with that.
        assertEquals(0, Reputation.score(1000, 1, log(200, 150, 9, 365), NOW));
    }

    @Test
    void owingCapsItRegardlessOfHistory() {
        assertEquals(1, Reputation.score(-3, 0, log(200, 150, 9, 365), NOW));
        assertTrue(Reputation.reasons(-3, 0, log(200, 150, 9, 365), NOW).stream()
                .anyMatch(reason -> reason.startsWith("owes ")));
    }

    @Test
    void eachPointIsAFactSomebodyCouldCheck() {
        // A history and nothing else.
        assertEquals(1, Reputation.score(5, 0, log(3, 0, 0, 0), NOW));
        // ...that has actually used the service.
        assertEquals(2, Reputation.score(5, 0, log(3, 2, 0, 0), NOW));
        // ...and paid real money for coins, which is the one an attacker cannot fake cheaply.
        assertEquals(3, Reputation.score(5, 0, log(3, 2, 1, 0), NOW));
        // ...and has dealt with several different wallets, not the same one over and over.
        assertEquals(4, Reputation.score(5, 0, log(3, 2, 1, 0, 4), NOW));
        // ...and has been around longer than a throwaway would have planned for.
        assertEquals(5, Reputation.score(5, 0, log(3, 2, 1, 8, 4), NOW));
    }

    @Test
    void threeThrowawaysAreNotAReputation() {
        // Counting distinct counterparties alone would hand a point to anybody who made three
        // wallets in a minute. What earns it is dealing with wallets that have something to lose.
        Map<String, Long> throwaways = Map.of("entries", 9L, "calls", 2L, "purchases", 1L,
                "counterparties", 5L, "solid_counterparties", 0L, "first_seen", NOW - 2 * DAY);
        assertEquals(3, Reputation.score(5, 0, throwaways, NOW));
        assertTrue(Reputation.reasons(5, 0, throwaways, NOW).stream()
                .anyMatch(reason -> reason.contains("none of them established")));
    }

    @Test
    void payingYourselfInACircleEarnsNothing() {
        // Volume is easy to manufacture; distinct counterparties are the part that needs other
        // people to agree to deal with you.
        assertEquals(3, Reputation.score(5, 0, log(80, 40, 1, 0, 1), NOW),
                "eighty entries with one counterparty is one relationship, not a reputation");
    }

    @Test
    void aWalletThatOnlyEverReceivedSaysSo() {
        // "0 purchases, only received coins" — the reasons make that visible rather than leaving a
        // bare number to be read as approval.
        var reasons = Reputation.reasons(50, 0, log(4, 0, 0, 1), NOW);
        assertTrue(reasons.stream().anyMatch(r -> r.equals("has never bought coins")));
        assertTrue(reasons.stream().anyMatch(r -> r.contains("never spent anything on a call")));
        assertEquals(1, Reputation.score(50, 0, log(4, 0, 0, 1), NOW), "receiving alone earns nothing");
    }
}
