package com.aicoin.proxy;

import io.netty.channel.EventLoopGroup;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One paid call this proxy originates: hold a coin, call the provider, settle what the call really
 * cost, refund if it never answered.
 *
 * <p>The accounting is the same one {@link UpstreamForwarder} applies to a forwarded call, and it
 * has to stay the same one, which is why it lives here rather than being written out at each call
 * site. A consortium turn and a {@code POST /text} call are both ordinary paid calls; a second
 * copy of hold-call-settle-refund is a second place for the refund to be forgotten.
 */
final class BilledCall {

    /** The hold placed before the call — the same one coin every other paid call holds. */
    static final double CALL_COST_AICOIN = 1.0;

    private BilledCall() {
    }

    /** What one billed call came to. Exactly one of insufficient / error / response is meaningful. */
    static final class Outcome {
        private final boolean insufficient;
        private final boolean attempted;
        private final String error;
        private final UpstreamCall.Result response;
        private final long charged;

        private Outcome(boolean insufficient, boolean attempted, String error,
                         UpstreamCall.Result response, long charged) {
            this.insufficient = insufficient;
            this.attempted = attempted;
            this.error = error;
            this.response = response;
            this.charged = charged;
        }

        /** The wallet could not pay for this call. Nothing was sent and nothing was charged. */
        boolean isInsufficient() {
            return insufficient;
        }

        /**
         * Whether the provider was actually called. True even when the call then failed and was
         * refunded — a caller counting attempts is counting these, not successes.
         */
        boolean wasAttempted() {
            return attempted;
        }

        /** Why there is no usable response, or null when there is one. */
        String getError() {
            return error;
        }

        /** The provider's 2xx response. Null unless {@link #getError()} is null. */
        UpstreamCall.Result getResponse() {
            return response;
        }

        /** Coins actually charged for this call: 1 under flat billing, the metered cost otherwise. */
        long getCharged() {
            return charged;
        }
    }

    /**
     * Holds a coin against {@code wallet}, POSTs to the provider, and settles.
     *
     * <p>The callback runs exactly once. A provider that never answered is refunded in full before
     * it does — the wallet paid for an answer it did not get, and there is no case in which keeping
     * that coin is right.
     */
    static void post(EventLoopGroup group, ProxyConfig config, ProviderHealthTracker healthTracker,
                      AicoinLedger ledger, String wallet, String provider, String path,
                      List<Map.Entry<String, String>> headers, byte[] body, Consumer<Outcome> onDone) {
        post(group, config, healthTracker, ledger, wallet, provider, path, headers, body, null, onDone);
    }

    /**
     * @param billingModel the model this proxy asked for, when the response will not say — an image
     *                     or speech call, priced by that model's configured per-call rate rather
     *                     than by a token count that is not coming. Null for chat, whose responses
     *                     report their own usage.
     */
    static void post(EventLoopGroup group, ProxyConfig config, ProviderHealthTracker healthTracker,
                      AicoinLedger ledger, String wallet, String provider, String path,
                      List<Map.Entry<String, String>> headers, byte[] body, String billingModel,
                      Consumer<Outcome> onDone) {
        ledger.debitForCall(wallet, CALL_COST_AICOIN, provider, debit -> {
            if (!debit.isReachable()) {
                onDone.accept(new Outcome(false, false, "could not validate wallet", null, 0));
                return;
            }
            if (!debit.isSuccess()) {
                onDone.accept(new Outcome(true, false, "insufficient balance", null, 0));
                return;
            }
            UpstreamCall.post(group, config, healthTracker, provider, path, headers, body, result -> {
                if (!result.isOk()) {
                    ledger.refund(wallet, CALL_COST_AICOIN, provider);
                    onDone.accept(new Outcome(false, true, result.getError(), null, 0));
                    return;
                }
                // Price it from what the provider itself reported. An image or speech response
                // reports no tokens at all, which is why the model's per-call rate exists.
                CostCalculator.Priced priced = billingModel == null ? null
                        : CostCalculator.priceKnownModel(provider, billingModel, config.getModelPricing());
                if (priced == null) {
                    priced = CostCalculator.price(provider, result.bodyText(), config.getModelPricing());
                }
                ledger.recordEvent(provider, priced.getCostUsd(),
                        priced.isTokensKnown() ? priced.getTokens() : -1, Instant.now(), wallet);
                long charged = 1L;
                if (config.isMeteredBilling()) {
                    charged = CoinMeter.coinsFor(priced.getCostUsd(), config.getCoinValueUsd());
                    ledger.settleCall(wallet, charged - CALL_COST_AICOIN, provider);
                }
                onDone.accept(new Outcome(false, true, null, result, charged));
            });
        });
    }
}
