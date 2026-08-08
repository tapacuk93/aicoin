package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link AicoinLedger#buildRedisUri}, the pure (no connection opened) piece of the
 * Redis connection setup that decides between ACL username+password auth (required by production
 * MemoryDB for Valkey) and the prior password-only/no-auth behavior (every local/e2e Redis, which
 * has no ACL configured) — see CONTRACT.md's "Config" section, {@code redis.username}.
 */
class AicoinLedgerTest {

    @Test
    void noUsernameAndNoPasswordMeansNoAuthAtAll() {
        RedisURI uri = AicoinLedger.buildRedisUri("localhost", 6379, "", "", false);
        assertNull(uri.getPassword());
    }

    @Test
    void passwordOnlyKeepsThePriorBehaviorUnchanged() {
        RedisURI uri = AicoinLedger.buildRedisUri("localhost", 6379, "", "s3cret", false);
        assertArrayEquals("s3cret".toCharArray(), uri.getPassword());
        assertNull(uri.getUsername());
    }

    @Test
    void usernameAndPasswordUseAclAuthentication() {
        RedisURI uri = AicoinLedger.buildRedisUri("memorydb.example.com", 6379, "aicoin-proxy", "s3cret", true);
        assertEquals("aicoin-proxy", uri.getUsername());
        assertArrayEquals("s3cret".toCharArray(), uri.getPassword());
        assertTrue(uri.isSsl());
    }

    @Test
    void hostPortAndSslArePreservedRegardlessOfAuthMode() {
        RedisURI uri = AicoinLedger.buildRedisUri("redis.internal", 16379, "", "", true);
        assertEquals("redis.internal", uri.getHost());
        assertEquals(16379, uri.getPort());
        assertTrue(uri.isSsl());
    }

    @Test
    void nullUsernameIsTreatedTheSameAsEmpty() {
        RedisURI withNullUsername = AicoinLedger.buildRedisUri("localhost", 6379, null, "s3cret", false);
        assertNull(withNullUsername.getUsername());
        assertArrayEquals("s3cret".toCharArray(), withNullUsername.getPassword());
    }
}
