package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lettuce.core.RedisURI;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT.md's "Welcome" ({@code POST /wallet/api/welcome}), against the ledger's own script on a
 * real Redis — only when one is named by {@code AICOIN_TEST_REDIS_URL} (e.g. {@code
 * redis://localhost:6390/15}); skipped otherwise. The database is flushed, so never point it at a
 * ledger that matters.
 */
class WelcomeRedisTest {

    private static final String NEW = "a".repeat(64);
    private static final String OTHER = "b".repeat(64);
    private static final String THIRD = "c".repeat(64);
    private static final int POOL = 5000;

    private AicoinLedger ledger;
    private RedisAsyncCommands<String, String> redis;

    @BeforeEach
    void connect() throws Exception {
        String url = System.getenv("AICOIN_TEST_REDIS_URL");
        Assumptions.assumeTrue(url != null && !url.isEmpty(), "AICOIN_TEST_REDIS_URL not set");
        ledger = new AicoinLedger(RedisURI.create(url));
        redis = ledger.redis();
        redis.flushdb().get();
    }

    @AfterEach
    void close() {
        if (ledger != null) {
            ledger.close();
        }
    }

    private static <T> T await(Consumer<Consumer<T>> call) throws Exception {
        CompletableFuture<T> f = new CompletableFuture<>();
        call.accept(f::complete);
        return f.get(5, TimeUnit.SECONDS);
    }

    private String welcome(String wallet, int cap) throws Exception {
        Optional<String> r = await(cb -> ledger.claimWelcome(wallet, POOL, 100.0, cap, cb));
        return r.orElseThrow();
    }

    private double balance(String wallet) throws Exception {
        Optional<Double> b = await(cb -> ledger.getBalance(wallet, cb));
        return b.orElseThrow();
    }

    private void emptyPool() throws Exception {
        redis.set("aicoin:{aicoin}:free-coins-remaining", "0").get();
    }

    @Test
    void aNewWalletGetsExactlyOneHundredOnce() throws Exception {
        emptyPool(); // the welcome never depends on the pool while under the cap
        assertEquals("welcome", welcome(NEW, 200));
        assertEquals(100.0, balance(NEW));
        assertEquals("already_claimed", welcome(NEW, 200));
        assertEquals("already_claimed", welcome(NEW, 200));
        assertEquals(100.0, balance(NEW));
        assertEquals("0", redis.get("aicoin:{aicoin}:free-coins-remaining").get());
    }

    @Test
    void aWalletThatClaimedTheFaucetBeforeIsNotWelcomedAgain() throws Exception {
        CompletableFuture<AicoinLedger.ClaimResult> f = new CompletableFuture<>();
        ledger.claimFreeCoins(NEW, 0, POOL, 100.0, f::complete);
        f.get(5, TimeUnit.SECONDS);
        assertEquals(100.0, balance(NEW));
        // A pool that still holds coins, and no cooldown: the hourly faucet would grant again.
        assertEquals("already_claimed", welcome(NEW, 200));
        assertEquals(100.0, balance(NEW));
    }

    @Test
    void anExistingBalanceIsAddedToNotReplaced() throws Exception {
        redis.set(AicoinLedger.balanceKey(NEW), "42").get();
        assertEquals("welcome", welcome(NEW, 200));
        assertEquals(142.0, balance(NEW));
    }

    @Test
    void pastTheDailyCapTheWelcomeComesFromThePoolOrWaits() throws Exception {
        assertEquals("welcome", welcome(NEW, 1));
        // Cap reached, the pool still holds coins: granted from it, and counted against it.
        assertEquals("pool", welcome(OTHER, 1));
        assertEquals(100.0, balance(OTHER));
        assertEquals(String.valueOf(POOL - 100), redis.get("aicoin:{aicoin}:free-coins-remaining").get());
        // Cap reached and the pool empty: nothing happens, and the wallet stays new for another day.
        emptyPool();
        assertEquals("welcome_limit", welcome(THIRD, 1));
        assertEquals(0.0, balance(THIRD));
        redis.del(AicoinLedger.welcomeCountKey(Instant.now())).get();
        assertEquals("welcome", welcome(THIRD, 1));
        assertEquals(100.0, balance(THIRD));
    }

    @Test
    void theDailyCountIsKeptTwoDays() throws Exception {
        welcome(NEW, 200);
        String key = AicoinLedger.welcomeCountKey(Instant.now());
        assertEquals("1", redis.get(key).get());
        long ttl = redis.ttl(key).get();
        assertEquals(true, ttl > 0 && ttl <= 172800);
    }
}
