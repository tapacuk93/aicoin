package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A wallet's standing, 0 to 5, per CONTRACT.md's "Reputation".
 *
 * <p>The distinction the number exists to make: <b>no history is not the same as a clean
 * history</b>. A wallet created a minute ago to take one payment and vanish has never done anything
 * wrong, and neither has one that has paid for a year. They score 0 and 5.
 *
 * <p>Every point is a fact the ledger already holds, and the reasons are published beside the score
 * — a number on its own would say "trustworthy" with an authority nothing here can back. What it
 * measures is exposure, not honesty: a wallet that has bought coins with real money and spent them
 * on calls has something to lose, and that is all a stranger can usefully know before accepting a
 * payment from it.
 */
final class Reputation {

    /** Old enough that a throwaway would have had to be created a week in advance. */
    private static final long ESTABLISHED_MILLIS = 7L * 24 * 60 * 60 * 1000;

    /** Wallets in good standing dealt with before that counts for anything. Two is not a network, but it is not nobody. */
    private static final long DEALT_WITH = 2;

    private Reputation() {
    }

    /** Whole coins as whole numbers, so a debt of three reads as 3 rather than 3.0. */
    private static String formatAmount(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * @param balance   what the wallet holds; negative means it owes
     * @param summary   counts from its transaction log — see {@link AicoinLedger#walletSummary}
     * @param nowMillis the clock, passed in so this stays a pure function
     */
    static int score(double balance, Map<String, Long> summary, long nowMillis) {
        // Owing is the one hard mark against a wallet: it has spent money it did not have, and
        // until it is paid the ledger will not let it spend more.
        if (balance < 0) {
            return 1;
        }
        long entries = summary.getOrDefault("entries", 0L);
        if (entries == 0) {
            // Never done anything. Not suspicious, not reassuring — unknown, which is its own
            // answer and must not be dressed up as a clean record.
            return 0;
        }
        int points = 1; // has a history and owes nothing
        if (summary.getOrDefault("calls", 0L) > 0) {
            points++;
        }
        if (summary.getOrDefault("purchases", 0L) > 0) {
            // Real money, spent through Apple. The one signal here that costs an attacker
            // something to fake at scale.
            points++;
        }
        if (summary.getOrDefault("solid_counterparties", 0L) >= DEALT_WITH) {
            // Dealings with several different wallets that themselves have something to lose.
            // Distinct counterparties alone would be worth a point for three throwaways created in
            // a minute; what is counted here is wallets that are clean, solvent and have actually
            // used the thing — which somebody else had to build first.
            points++;
        }
        long firstSeen = summary.getOrDefault("first_seen", 0L);
        if (firstSeen > 0 && nowMillis - firstSeen >= ESTABLISHED_MILLIS) {
            points++;
        }
        return Math.min(points, 5);
    }

    /** The reasons, in the order they matter, so the number is never the whole of what is shown. */
    static List<String> reasons(double balance, Map<String, Long> summary, long nowMillis) {
        List<String> reasons = new ArrayList<>();
        if (balance < 0) {
            reasons.add("owes " + formatAmount(-balance) + " aicoin");
        }
        if (summary.getOrDefault("entries", 0L) == 0) {
            reasons.add("no history at all — this wallet has never done anything");
            return reasons;
        }
        if (summary.getOrDefault("purchases", 0L) > 0) {
            reasons.add("has bought coins with real money");
        } else {
            reasons.add("has never bought coins");
        }
        if (summary.getOrDefault("calls", 0L) > 0) {
            reasons.add(summary.get("calls") + " paid call" + (summary.get("calls") == 1 ? "" : "s") + " made");
        } else {
            reasons.add("has never spent anything on a call");
        }
        long counterparties = summary.getOrDefault("counterparties", 0L);
        long solid = summary.getOrDefault("solid_counterparties", 0L);
        if (counterparties == 0) {
            reasons.add("has never dealt with another wallet");
        } else if (solid == 0) {
            reasons.add("has dealt with " + counterparties + " wallet" + (counterparties == 1 ? "" : "s")
                    + ", none of them established");
        } else {
            reasons.add("has dealt with " + counterparties + " wallet" + (counterparties == 1 ? "" : "s")
                    + ", " + solid + " of them established");
        }
        long firstSeen = summary.getOrDefault("first_seen", 0L);
        if (firstSeen > 0) {
            long days = (nowMillis - firstSeen) / (24 * 60 * 60 * 1000);
            reasons.add("oldest record still held is " + days + " day" + (days == 1 ? "" : "s") + " old");
        }
        return reasons;
    }
}
