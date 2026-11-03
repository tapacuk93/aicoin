package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisURI;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT.md's "Linked wallets", against the ledger's own scripts on a real Redis — only when one
 * is named by {@code AICOIN_TEST_REDIS_URL} (e.g. {@code redis://localhost:6390/15}); skipped
 * otherwise. The database is flushed, so never point it at a ledger that matters.
 */
class LinkedWalletsRedisTest {

    private static final String OWNER = "a".repeat(64);
    private static final String LINKED = "b".repeat(64);
    private static final String OTHER = "c".repeat(64);
    private static final String PLAIN = "d".repeat(64);

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

    private void setBalance(String wallet, double coins) throws Exception {
        redis.set(AicoinLedger.balanceKey(wallet), String.valueOf(coins)).get();
    }

    private double balance(String wallet) throws Exception {
        Optional<Double> b = await(cb -> ledger.getBalance(wallet, cb));
        return b.orElseThrow();
    }

    private AicoinLedger.Spending spending(String wallet) throws Exception {
        Optional<AicoinLedger.Spending> s = await(cb -> ledger.getSpending(wallet, cb));
        return s.orElseThrow();
    }

    private AicoinLedger.LinkResult link(String owner, String linked, String app, Double limit) throws Exception {
        return await(cb -> ledger.link(owner, linked, app, limit, cb));
    }

    private AicoinLedger.DebitResult debit(String wallet) throws Exception {
        return await(cb -> ledger.debitForCall(wallet, 1.0, "openai", cb));
    }

    private List<Map<?, ?>> log(String wallet) throws Exception {
        return redis.lrange(AicoinLedger.txKey(wallet), 0, -1).get().stream()
                .<Map<?, ?>>map(e -> (Map<?, ?>) Json.load(e)).toList();
    }

    // ---- create / update / list / remove ----------------------------------------------------------

    @Test
    void aLinkIsMadeListedUpdatedKeepingSpentAndRemoved() throws Exception {
        AicoinLedger.LinkResult made = link(OWNER, LINKED, "AI UI", 500.0);
        assertTrue(made.reachable());
        assertNull(made.error());
        assertEquals("AI UI", made.link().app());
        assertEquals(500.0, made.link().limit());
        assertEquals(0.0, made.link().spent());
        assertTrue(made.link().linked() > 0);
        assertEquals("{\"address\":\"" + LINKED + "\",\"app\":\"AI UI\",\"limit\":500,\"spent\":0,\"linked\":"
                + made.link().linked() + "}", made.link().toJson());

        setBalance(OWNER, 100);
        assertTrue(debit(LINKED).isSuccess());

        // Re-sent by the same owner: app and limit change, spent and the link time stay.
        AicoinLedger.LinkResult updated = link(OWNER, LINKED, "AI UI 2", null);
        assertNull(updated.error());
        assertEquals("AI UI 2", updated.link().app());
        assertNull(updated.link().limit());
        assertEquals(1.0, updated.link().spent());
        assertEquals(made.link().linked(), updated.link().linked());
        assertTrue(updated.link().toJson().contains("\"limit\":null"));

        Optional<List<AicoinLedger.Link>> links = await(cb -> ledger.listLinks(OWNER, cb));
        assertEquals(1, links.orElseThrow().size());
        assertEquals(LINKED, links.get().get(0).address());
        assertEquals("AI UI 2", links.get().get(0).app());

        Optional<Integer> removed = await(cb -> ledger.unlink(OWNER, LINKED, cb));
        assertEquals(1, removed.orElseThrow());
        assertTrue(await((Consumer<Optional<List<AicoinLedger.Link>>> cb) -> ledger.listLinks(OWNER, cb))
                .orElseThrow().isEmpty());
        assertFalse(spending(LINKED).isLinked());
        // Removing again finds nothing.
        assertEquals(0, await((Consumer<Optional<Integer>> cb) -> ledger.unlink(OWNER, LINKED, cb)).orElseThrow());
    }

    @Test
    void linksAreListedNewestFirst() throws Exception {
        link(OWNER, LINKED, "first", null);
        Thread.sleep(5);
        link(OWNER, OTHER, "second", null);
        List<AicoinLedger.Link> links = await((Consumer<Optional<List<AicoinLedger.Link>>> cb) ->
                ledger.listLinks(OWNER, cb)).orElseThrow();
        assertEquals(List.of(OTHER, LINKED), links.stream().map(AicoinLedger.Link::address).toList());
    }

    @Test
    void theLinkedWalletMayLeaveButAStrangerMayNotRemoveIt() throws Exception {
        link(OWNER, LINKED, "AI UI", null);
        assertEquals(-1, await((Consumer<Optional<Integer>> cb) -> ledger.unlink(OTHER, LINKED, cb)).orElseThrow());
        assertTrue(spending(LINKED).isLinked());
        assertEquals(1, await((Consumer<Optional<Integer>> cb) -> ledger.unlink(LINKED, LINKED, cb)).orElseThrow());
        assertFalse(spending(LINKED).isLinked());
    }

    // ---- the 409 shapes -------------------------------------------------------------------------------

    @Test
    void aWalletLinkedToAnotherOwnerIsAlreadyLinked() throws Exception {
        link(OWNER, LINKED, "AI UI", null);
        assertEquals("already_linked", link(OTHER, LINKED, "AI UI", null).error());
    }

    @Test
    void aWalletWithLinksOfItsOwnCannotBeLinked() throws Exception {
        link(OWNER, LINKED, "AI UI", null);
        // OWNER has a link, so it cannot itself become linked.
        assertEquals("is_owner", link(OTHER, OWNER, "x", null).error());
    }

    @Test
    void aLinkedWalletCannotBecomeAnOwner() throws Exception {
        link(OWNER, LINKED, "AI UI", null);
        // The signer is itself linked: no chains.
        assertEquals("is_owner", link(LINKED, OTHER, "x", null).error());
        assertFalse(spending(OTHER).isLinked());
    }

    // ---- who pays ---------------------------------------------------------------------------------------

    @Test
    void aLinkedWalletsDebitIsTakenFromTheOwner() throws Exception {
        setBalance(OWNER, 10);
        setBalance(LINKED, 0);
        link(OWNER, LINKED, "AI UI", null);

        AicoinLedger.DebitResult d = debit(LINKED);
        assertTrue(d.isSuccess());
        assertTrue(d.isLinked());
        assertEquals(0.0, d.getBalance(), "the result carries the linked wallet's own balance");
        assertEquals(9.0, d.getSpendable());
        assertEquals(9.0, balance(OWNER));
        assertEquals(0.0, balance(LINKED));
        assertEquals(1.0, spending(LINKED).spent());

        List<Map<?, ?>> ownerLog = log(OWNER);
        assertEquals(1, ownerLog.size());
        assertEquals("debit", ownerLog.get(0).get("type"));
        assertEquals(LINKED, ownerLog.get(0).get("linked"));
        assertTrue(log(LINKED).isEmpty(), "the linked wallet's log records nothing for it");

        // Settlement and refund also go to the owner.
        ledger.settleCall(LINKED, 2.5, "openai");
        ledger.refund(LINKED, 1.0, "openai");
        assertEquals(7.5, balance(OWNER));
        assertEquals(0.0, balance(LINKED));
        assertEquals(2.5, spending(LINKED).spent());
        List<Map<?, ?>> after = log(OWNER);
        assertEquals(List.of("debit", "debit_settlement", "refund"),
                after.stream().map(e -> e.get("type")).toList());
        assertTrue(after.stream().allMatch(e -> LINKED.equals(e.get("linked"))));
    }

    @Test
    void aWalletNoLongerLinkedPaysForItselfAgain() throws Exception {
        setBalance(OWNER, 10);
        setBalance(LINKED, 3);
        link(OWNER, LINKED, "AI UI", null);
        await((Consumer<Optional<Integer>> cb) -> ledger.unlink(LINKED, LINKED, cb));
        AicoinLedger.DebitResult d = debit(LINKED);
        assertTrue(d.isSuccess());
        assertFalse(d.isLinked());
        assertEquals(2.0, balance(LINKED));
        assertEquals(10.0, balance(OWNER));
    }

    @Test
    void anUnlinkedWalletIsDebitedAsBefore() throws Exception {
        setBalance(PLAIN, 2);
        AicoinLedger.DebitResult d = debit(PLAIN);
        assertTrue(d.isSuccess());
        assertFalse(d.isLinked());
        assertEquals(1.0, d.getBalance());
        assertEquals(1.0, balance(PLAIN));
        assertNull(log(PLAIN).get(0).get("linked"));
        setBalance(PLAIN, 0.5);
        AicoinLedger.DebitResult refused = debit(PLAIN);
        assertFalse(refused.isSuccess());
        assertEquals("{\"error\":\"insufficient aicoin balance\",\"balance\":0.5}", refused.insufficientJson());
    }

    // ---- the limit ----------------------------------------------------------------------------------------

    @Test
    void theLimitRefusesWithLinkLimitAndSpendable() throws Exception {
        setBalance(OWNER, 100);
        setBalance(LINKED, 4);
        link(OWNER, LINKED, "AI UI", 2.0);

        assertTrue(debit(LINKED).isSuccess());
        assertTrue(debit(LINKED).isSuccess());
        AicoinLedger.DebitResult refused = debit(LINKED);
        assertFalse(refused.isSuccess());
        assertTrue(refused.isLinkLimit());
        assertEquals(0.0, refused.getSpendable());
        assertEquals("{\"error\":\"insufficient aicoin balance\",\"balance\":4,\"reason\":\"link_limit\",\"spendable\":0}",
                refused.insufficientJson());
        assertEquals(98.0, balance(OWNER));
        assertEquals(4.0, balance(LINKED), "its own coins are not spent instead");

        // A refund takes spent back down, so the next call goes through.
        ledger.refund(LINKED, 1.0, "openai");
        assertEquals(1.0, spending(LINKED).spent());
        assertTrue(debit(LINKED).isSuccess());

        // Raising the limit lets it spend again; lowering it to spent stops it at once.
        link(OWNER, LINKED, "AI UI", 5.0);
        assertTrue(debit(LINKED).isSuccess());
        link(OWNER, LINKED, "AI UI", 3.0);
        assertTrue(debit(LINKED).isLinkLimit());
    }

    @Test
    void anEmptyOwnerIsNotALinkLimit() throws Exception {
        setBalance(OWNER, 0.5);
        link(OWNER, LINKED, "AI UI", 50.0);
        AicoinLedger.DebitResult refused = debit(LINKED);
        assertFalse(refused.isSuccess());
        assertFalse(refused.isLinkLimit());
        assertEquals("{\"error\":\"insufficient aicoin balance\",\"balance\":0,\"spendable\":0.5}",
                refused.insufficientJson());
        assertEquals(0.0, spending(LINKED).spent());
    }

    @Test
    void aRefundNeverTakesSpentBelowZero() throws Exception {
        setBalance(OWNER, 10);
        link(OWNER, LINKED, "AI UI", 5.0);
        ledger.refund(LINKED, 1.0, "openai");
        assertEquals(0.0, spending(LINKED).spent());
        assertEquals(11.0, balance(OWNER));
    }

    // ---- the balance fields -------------------------------------------------------------------------------

    @Test
    void theBalanceOfALinkedWalletCarriesItsLink() throws Exception {
        setBalance(OWNER, 40);
        setBalance(LINKED, 3);
        link(OWNER, LINKED, "AI UI", 10.0);
        debit(LINKED);
        debit(LINKED);

        AicoinLedger.Spending s = spending(LINKED);
        assertEquals(3.0, s.balance());
        assertEquals(OWNER, s.linkedTo());
        assertEquals(10.0, s.limit());
        assertEquals(2.0, s.spent());
        assertEquals(8.0, s.spendable(), "min(owner 38, limit 10 - spent 2)");
        assertEquals(",\"linked_to\":\"" + OWNER + "\",\"limit\":10,\"spent\":2,\"spendable\":8", s.balanceFieldsJson());

        // No limit: everything the owner has.
        link(OWNER, LINKED, "AI UI", null);
        AicoinLedger.Spending unlimited = spending(LINKED);
        assertEquals(38.0, unlimited.spendable());
        assertEquals(",\"linked_to\":\"" + OWNER + "\",\"limit\":null,\"spent\":2,\"spendable\":38",
                unlimited.balanceFieldsJson());
    }

    @Test
    void anyOtherWalletsSpendableIsItsBalance() throws Exception {
        setBalance(PLAIN, 7);
        AicoinLedger.Spending s = spending(PLAIN);
        assertFalse(s.isLinked());
        assertEquals(7.0, s.spendable());
        assertEquals(",\"spendable\":7", s.balanceFieldsJson());
        // An owner is not linked: its own balance is what it spends.
        link(OWNER, LINKED, "AI UI", null);
        setBalance(OWNER, 5);
        assertEquals(",\"spendable\":5", spending(OWNER).balanceFieldsJson());
    }
}
