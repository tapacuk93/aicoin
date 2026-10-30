package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PhoneStore.Redis}'s scripts against a real Redis — only when one is named by
 * {@code AICOIN_TEST_REDIS_URL} (e.g. {@code redis://localhost:6390/15}); skipped otherwise. The
 * database is flushed, so never point it at a ledger that matters.
 */
class PhoneStoreRedisTest {

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private PhoneStore.Redis store;
    private RedisAsyncCommands<String, String> redis;

    @BeforeEach
    void connect() throws Exception {
        String url = System.getenv("AICOIN_TEST_REDIS_URL");
        Assumptions.assumeTrue(url != null && !url.isEmpty(), "AICOIN_TEST_REDIS_URL not set");
        client = RedisClient.create(url);
        connection = client.connect();
        redis = connection.async();
        redis.flushdb().get();
        store = new PhoneStore.Redis(redis);
    }

    @AfterEach
    void close() {
        if (connection != null) {
            connection.close();
            client.shutdown();
        }
    }

    private static PhoneRecord record(String wallet, String phoneId) {
        PhoneRecord r = new PhoneRecord();
        r.wallet = wallet;
        r.phoneId = phoneId;
        r.tier = "cpu";
        r.instanceType = "c8i.2xlarge";
        r.coinsPerMinute = 2;
        r.token = "tok";
        r.launchedAt = 1000;
        return r;
    }

    @Test
    void createIsOncePerWalletAndIndexed() {
        assertTrue(store.create(record("w1", "ph-1")));
        assertFalse(store.create(record("w1", "ph-2")));
        assertEquals("ph-1", store.get("w1").phoneId);
        assertEquals(List.of("w1"), store.wallets());
    }

    @Test
    void chargeDebitsTheLedgerBalanceOncePerMinuteAndLogsIt() throws Exception {
        redis.set(AicoinLedger.balanceKey("w1"), "10").get();
        store.create(record("w1", "ph-1"));
        PhoneStore.Charge c = store.charge("w1", "ph-1", 3, 5000);
        assertTrue(c.found());
        assertEquals(3, c.minutes());
        assertEquals(6, c.coins());
        assertEquals(4.0, c.balance(), 1e-9);
        assertEquals(0, store.charge("w1", "ph-1", 3, 6000).minutes(), "already charged");
        // Into debt rather than refusing: the minute ran.
        assertEquals(-2.0, store.charge("w1", "ph-1", 6, 7000).balance(), 1e-9);
        PhoneRecord r = store.get("w1");
        assertEquals(6, r.billedMinutes);
        assertEquals(12, r.chargedCoins);
        assertFalse(store.charge("w1", "ph-other", 9, 8000).found());
        List<String> tx = redis.lrange(AicoinLedger.txKey("w1"), 0, -1).get();
        assertEquals(2, tx.size());
        assertTrue(tx.get(0).contains("\"provider\":\"anyphone\""), tx.get(0));
        assertTrue(redis.sismember(AicoinLedger.KNOWN_WALLETS_KEY, "w1").get());
    }

    @Test
    void saveIsCompareAndSetAndNeverTouchesTheCharges() {
        store.create(record("w1", "ph-1"));
        PhoneRecord stale = store.get("w1");
        store.charge("w1", "ph-1", 2, 1);
        stale.state = PhoneRecord.RUNNING;
        assertFalse(store.save(stale), "a charge happened since it was read");
        PhoneRecord fresh = store.get("w1");
        fresh.state = PhoneRecord.RUNNING;
        fresh.host = "203.0.113.1";
        fresh.instanceId = "i-1";
        assertTrue(store.save(fresh));
        PhoneRecord after = store.get("w1");
        assertEquals("running", after.state);
        assertEquals("i-1", after.instanceId);
        assertEquals(2, after.billedMinutes);
        assertEquals(fresh.version, after.version);
    }

    @Test
    void deleteOnlyTheSamePhoneAndLeaseIsExclusive() {
        store.create(record("w1", "ph-1"));
        assertFalse(store.delete("w1", "ph-2"));
        assertTrue(store.delete("w1", "ph-1"));
        assertNull(store.get("w1"));
        assertTrue(store.wallets().isEmpty());

        assertTrue(store.lease("a", 60_000));
        assertTrue(store.lease("a", 60_000));
        assertFalse(store.lease("b", 60_000));
    }
}
