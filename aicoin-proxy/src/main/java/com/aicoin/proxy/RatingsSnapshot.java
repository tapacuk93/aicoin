package com.aicoin.proxy;

import java.util.List;

/**
 * Every wallet's rating, in one signed document that wallets carry so a rating can be checked with
 * no network — per CONTRACT.md's "Ratings snapshot".
 *
 * <p>A rating is only useful at the moment somebody is deciding whether to accept a payment, and
 * that moment is exactly when there is no network: that is what offline payment means. So the
 * ledger signs the whole list, wallets download it while they can, and the confirmation dialog
 * reads it from disk.
 *
 * <p>What it cannot be is fresh. A rating can drop to zero the instant a double-spend is proven,
 * and a snapshot from this morning will not know. So the document carries the time it was made,
 * every client shows that age beside the number, and nobody is invited to read a day-old 5 as a
 * statement about right now.
 *
 * <p>The signed text is canonical and boring on purpose — sorted, one wallet per line, no JSON
 * whitespace to disagree about — because two implementations have to hash exactly the same bytes.
 */
final class RatingsSnapshot {

    /** Most wallets in one snapshot. A list that grows without limit cannot be downloaded by the phone that needs it. */
    static final int MAX_WALLETS = 5_000;

    private RatingsSnapshot() {
    }

    /**
     * The exact bytes signed: a header, the issue time, then {@code <address>:<rating>} per line,
     * sorted by address.
     */
    static String canonical(long issuedAtMillis, List<String[]> ratings) {
        StringBuilder out = new StringBuilder("aicoin-ratings\n").append(issuedAtMillis).append("\n");
        for (String[] row : ratings) {
            out.append(row[0]).append(':').append(row[1]).append('\n');
        }
        return out.toString();
    }

    /** The document a wallet stores, with the signature that makes it checkable offline. */
    static String json(long issuedAtMillis, List<String[]> ratings, String signatureHex) {
        StringBuilder out = new StringBuilder("{\"issued_at\":").append(issuedAtMillis)
                .append(",\"count\":").append(ratings.size())
                .append(",\"max\":").append(MAX_WALLETS)
                .append(",\"ratings\":{");
        boolean first = true;
        for (String[] row : ratings) {
            out.append(first ? "" : ",").append('"').append(row[0]).append("\":").append(row[1]);
            first = false;
        }
        out.append("},\"signature\":\"").append(signatureHex).append("\"}");
        return out.toString();
    }
}
