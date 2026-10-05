package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Nothing that could carry a credential travels back out of the proxy.
 *
 * <p>The premise of this proxy is that a client never holds a provider key: the key is injected on
 * the way out and the client is given the answer, not the credential. The response path used to
 * copy every upstream header back verbatim, which made that promise depend on a property of
 * somebody else's server — that no provider we route to ever reflects its own auth header, or sets
 * a cookie bound to the proxy's account. That is not a property this code can check, and it is one
 * a provider can change without telling anyone.
 *
 * <p>So the ones that could carry a credential are dropped, and this pins that. Everything else is
 * passed through untouched: content type and encoding are what the client actually needs, and
 * rate-limit headers are what a caller uses to back off.
 */
class UpstreamHeaderLeakTest {

    private static HttpHeaders copied(HttpHeaders from, ProviderConfig provider) throws Exception {
        Class<?> handler = Class.forName("com.aicoin.proxy.UpstreamForwarder$UpstreamResponseHandler");
        Method copy = handler.getDeclaredMethod("copyHeaders", HttpHeaders.class, HttpHeaders.class, ProviderConfig.class);
        copy.setAccessible(true);
        HttpHeaders to = new DefaultHttpHeaders();
        copy.invoke(null, from, to, provider);
        return to;
    }

    private static ProviderConfig provider(String authHeader) {
        return new ProviderConfig("https://api.example.com", "sk-secret", authHeader, "Bearer ", false, null, List.of());
    }

    @Test
    void anEchoedAuthHeaderNeverReachesTheClient() throws Exception {
        HttpHeaders upstream = new DefaultHttpHeaders();
        upstream.add("x-api-key", "sk-secret");
        upstream.add("Authorization", "Bearer sk-secret");
        upstream.add("Content-Type", "application/json");

        HttpHeaders out = copied(upstream, provider("x-api-key"));

        assertNull(out.get("x-api-key"), "the provider echoed its own auth header and it was passed straight through");
        assertNull(out.get("Authorization"), "an echoed bearer token is the same disclosure by another name");
        assertEquals("application/json", out.get("Content-Type"));
    }

    /** Anthropic's header is `x-api-key`, OpenAI's is `Authorization`: both are dropped whichever provider answered. */
    @Test
    void theAuthHeaderIsDroppedForWhicheverProviderAnswered() throws Exception {
        HttpHeaders upstream = new DefaultHttpHeaders();
        upstream.add("x-api-key", "sk-secret");
        HttpHeaders out = copied(upstream, provider("Authorization"));
        assertNull(out.get("x-api-key"), "x-api-key is dropped for every provider, not only the one that uses it");
    }

    @Test
    void aCookieBoundToTheProxysAccountIsNotHandedToTheClient() throws Exception {
        HttpHeaders upstream = new DefaultHttpHeaders();
        upstream.add("Set-Cookie", "session=proxy-account-session; Path=/");
        assertNull(copied(upstream, provider("x-api-key")).get("Set-Cookie"));
    }

    /** The headers a caller genuinely needs must survive — a filter that drops everything is no use. */
    @Test
    void everythingElseIsPassedThroughUntouched() throws Exception {
        HttpHeaders upstream = new DefaultHttpHeaders();
        upstream.add("Content-Encoding", "gzip");
        upstream.add("x-ratelimit-remaining-requests", "42");
        upstream.add("request-id", "req_123");

        HttpHeaders out = copied(upstream, provider("x-api-key"));

        assertEquals("gzip", out.get("Content-Encoding"), "dropping this would break every compressed response");
        assertEquals("42", out.get("x-ratelimit-remaining-requests"));
        assertEquals("req_123", out.get("request-id"));
    }

    /** A provider configured for query-parameter auth (Google) has no auth header to drop. */
    @Test
    void aProviderWithNoAuthHeaderStillHasTheCommonOnesDropped() throws Exception {
        HttpHeaders upstream = new DefaultHttpHeaders();
        upstream.add("x-api-key", "sk-secret");
        upstream.add("Content-Type", "application/json");
        HttpHeaders out = copied(upstream, provider(null));
        assertNull(out.get("x-api-key"));
        assertEquals("application/json", out.get("Content-Type"));
    }
}
