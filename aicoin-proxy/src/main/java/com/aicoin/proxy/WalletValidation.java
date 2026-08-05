package com.aicoin.proxy;

import java.util.Optional;

/**
 * Pure-function pieces of wallet-id-as-API-key auth: extracting the
 * caller's {@code X-Api-Key} header value (an API token for AI-proxy calls,
 * or a bare address for the live-signed wallet-management endpoints — see
 * {@link WalletSignature} for how each is actually verified and how the
 * balance gate now works via {@link AicoinLedger#debitForCall}).
 */
public final class WalletValidation {

    static final String HEADER_NAME = "X-Api-Key";

    private WalletValidation() {
    }

    /**
     * Extracts the wallet id from the raw {@code X-Api-Key} header value.
     * A missing, null, or blank/whitespace-only header resolves to {@link
     * Optional#empty()}, which callers must turn into
     * {@code 401 {"error":"missing X-Api-Key (wallet id)"}}.
     */
    public static Optional<String> extractWalletId(String xApiKeyHeaderValue) {
        if (xApiKeyHeaderValue == null) {
            return Optional.empty();
        }
        String trimmed = xApiKeyHeaderValue.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }
}


