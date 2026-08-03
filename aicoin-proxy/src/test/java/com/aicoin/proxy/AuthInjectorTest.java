package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Auth-injection construction (header+prefix form and query-param form),
 * per CONTRACT.md's "Routing" section.
 */
class AuthInjectorTest {

    @Test
    void headerFormInjectsAuthorizationBearerForOpenAiLikeProvider() {
        ProviderConfig openai = new ProviderConfig(
                "https://api.openai.com", "test-key-123", "Authorization", "Bearer ", false, null);

        AuthInjector.Injection injection = AuthInjector.compute(openai);

        assertFalse(injection.isQueryParam());
        assertEquals("Authorization", injection.getName());
        assertEquals("Bearer test-key-123", injection.getValue());
    }

    @Test
    void headerFormInjectsXApiKeyWithNoPrefixForAnthropic() {
        ProviderConfig anthropic = new ProviderConfig(
                "https://api.anthropic.com", "anthropic-secret", "x-api-key", "", false, null);

        AuthInjector.Injection injection = AuthInjector.compute(anthropic);

        assertFalse(injection.isQueryParam());
        assertEquals("x-api-key", injection.getName());
        assertEquals("anthropic-secret", injection.getValue());
    }

    @Test
    void queryParamFormInjectsKeyForGoogle() {
        ProviderConfig google = new ProviderConfig(
                "https://generativelanguage.googleapis.com", "google-secret", null, null, true, "key");

        AuthInjector.Injection injection = AuthInjector.compute(google);

        assertTrue(injection.isQueryParam());
        assertEquals("key", injection.getName());
        assertEquals("google-secret", injection.getValue());
    }

    @Test
    void appendQueryParamAddsQuestionMarkWhenNoExistingQuery() {
        String uri = AuthInjector.appendQueryParam("/v1/models", "key", "abc123");
        assertEquals("/v1/models?key=abc123", uri);
    }

    @Test
    void appendQueryParamAddsAmpersandWhenQueryAlreadyPresent() {
        String uri = AuthInjector.appendQueryParam("/v1/models?stream=true", "key", "abc123");
        assertEquals("/v1/models?stream=true&key=abc123", uri);
    }

    @Test
    void appendQueryParamUrlEncodesValue() {
        String uri = AuthInjector.appendQueryParam("/v1/models", "key", "a b&c");
        assertEquals("/v1/models?key=a+b%26c", uri);
    }
}
