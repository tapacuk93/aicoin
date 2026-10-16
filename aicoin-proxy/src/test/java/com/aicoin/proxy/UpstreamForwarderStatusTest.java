package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.Test;

/**
 * A provider's own 402 must not reach a client as 402, which every client reads as "this wallet is
 * empty" — see {@link UpstreamForwarder#statusForClient}.
 */
class UpstreamForwarderStatusTest {

    @Test
    void upstreamPaymentRequiredIsNotRelayedAsTheWalletGate() {
        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE,
                UpstreamForwarder.statusForClient(HttpResponseStatus.PAYMENT_REQUIRED));
    }

    @Test
    void everyOtherStatusIsRelayedUnchanged() {
        for (HttpResponseStatus s : new HttpResponseStatus[] {
                HttpResponseStatus.OK, HttpResponseStatus.BAD_REQUEST, HttpResponseStatus.UNAUTHORIZED,
                HttpResponseStatus.FORBIDDEN, HttpResponseStatus.TOO_MANY_REQUESTS,
                HttpResponseStatus.INTERNAL_SERVER_ERROR}) {
            assertEquals(s, UpstreamForwarder.statusForClient(s));
        }
    }
}
