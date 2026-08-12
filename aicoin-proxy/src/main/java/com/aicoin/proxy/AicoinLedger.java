package com.aicoin.proxy;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-process replacement for the old Go aicoin node: wallet balances, the
 * free-coin faucet, peer transfers, and the recency-weighted price formula,
 * all backed by a single Redis instance (ElastiCache in production, a plain
 * {@code redis:7-alpine} container locally/in e2e). No signing, no chain, no
 * replication — this is a centralized ledger, not a blockchain.
 *
 * <p>Claim and transfer are check-then-mutate operations that must be atomic
 * against concurrent calls for the same wallet (two simultaneous claims
 * could otherwise both mint, or two simultaneous transfers could both pass a
 * stale balance check and overdraw); both run as single Redis Lua scripts,
 * which Redis executes atomically with respect to every other client.
 */
final class AicoinLedger implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AicoinLedger.class.getName());

    /**
     * Redis Cluster hash tag applied to <em>every</em> ledger key, so they all hash to one slot.
     *
     * <p>Required because production runs on AWS MemoryDB, which is <b>always</b> cluster-mode
     * (even at one shard) — and Redis Cluster rejects any multi-key command whose keys span
     * different slots with {@code CROSSSLOT}, regardless of whether those slots happen to live on
     * the same node. Every atomic operation here is inherently multi-key (a claim touches the
     * wallet's balance + last-claim time + the shared pool counter + the known-wallets set + the
     * wallet's tx log; a transfer touches two different wallets' balances), so per-wallet tagging
     * would not be enough — a transfer between two wallets would still cross slots. One fixed tag
     * for the whole ledger is the only scheme that keeps all of them single-slot.
     *
     * <p>The trade-off is deliberate and matches the design: this is a single centralized ledger
     * (see CONTRACT.md), so confining it to one slot costs nothing today. It does mean the ledger
     * cannot be spread across multiple shards — scaling out would require re-sharding the key
     * scheme and giving up cross-wallet atomicity, which is a much larger design change than a
     * bigger node.
     */
    private static final String TAG = "{aicoin}";
    private static final String EVENTS_KEY = "aicoin:" + TAG + ":events";
    private static final String FREE_COINS_REMAINING_KEY = "aicoin:" + TAG + ":free-coins-remaining";
    private static final String KNOWN_WALLETS_KEY = "aicoin:" + TAG + ":known-wallets";
    private static final String IAP_PACKAGES_KEY = "aicoin:" + TAG + ":iap-packages";
    private static final String OFFER_KEY = "aicoin:" + TAG + ":offer";
    /** The operator's total spend ceiling in USD — see CONTRACT.md's "Spend budget". Unset means no ceiling. */
    private static final String BUDGET_KEY = "aicoin:" + TAG + ":budget";
    /**
     * Wallet addresses whose spend does not count against {@link #BUDGET_KEY} — development and
     * QA installs. Membership is an operator decision written through {@code POST
     * /admin/internal-wallets}, never something a client can assert about itself: a build-type
     * header would let anyone exclude their own usage from the ceiling simply by sending it, which
     * defeats the point of having a ceiling.
     */
    private static final String INTERNAL_WALLETS_KEY = "aicoin:" + TAG + ":internal-wallets";
    /** Per-wallet transaction logs are capped at this many most-recent entries (draft/prototype: no pagination, no archival). */
    private static final int TX_LOG_CAP = 200;

    private static final String CLAIM_SCRIPT =
            "local now = tonumber(ARGV[1]) "
            + "local cooldown = tonumber(ARGV[2]) "
            + "local poolSize = tonumber(ARGV[3]) "
            + "local amount = tonumber(ARGV[4]) "
            + "local last = redis.call('GET', KEYS[1]) "
            + "if last and (now - tonumber(last)) < cooldown then "
            + "  return {0, tostring(tonumber(last) + cooldown), 'cooldown'} "
            + "end "
            + "local remainingRaw = redis.call('GET', KEYS[3]) "
            + "local remaining "
            + "if remainingRaw == false then remaining = poolSize else remaining = tonumber(remainingRaw) end "
            + "if remaining < amount then "
            + "  return {0, '0', 'exhausted'} "
            + "end "
            + "redis.call('SET', KEYS[1], ARGV[1]) "
            + "local newBalance = redis.call('INCRBYFLOAT', KEYS[2], ARGV[4]) "
            + "redis.call('SET', KEYS[3], remaining - amount) "
            + "redis.call('SADD', KEYS[4], ARGV[5]) "
            + "redis.call('RPUSH', KEYS[5], cjson.encode({type='claim', amount=amount, balance_after=tonumber(newBalance), at=now})) "
            + "redis.call('LTRIM', KEYS[5], -" + TX_LOG_CAP + ", -1) "
            + "return {1, tostring(now + cooldown), 'granted'}";

    private static final String TRANSFER_SCRIPT =
            "local amount = tonumber(ARGV[1]) "
            + "local from = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "if amount <= 0 or from < amount then "
            + "  return 0 "
            + "end "
            + "local newFromBalance = redis.call('INCRBYFLOAT', KEYS[1], '-' .. ARGV[1]) "
            + "local newToBalance = redis.call('INCRBYFLOAT', KEYS[2], ARGV[1]) "
            + "redis.call('SADD', KEYS[3], ARGV[2]) "
            + "redis.call('SADD', KEYS[3], ARGV[3]) "
            + "local now = tonumber(ARGV[4]) "
            + "redis.call('RPUSH', KEYS[4], cjson.encode({type='transfer_out', amount=amount, counterparty=ARGV[3], balance_after=tonumber(newFromBalance), at=now})) "
            + "redis.call('LTRIM', KEYS[4], -" + TX_LOG_CAP + ", -1) "
            + "redis.call('RPUSH', KEYS[5], cjson.encode({type='transfer_in', amount=amount, counterparty=ARGV[2], balance_after=tonumber(newToBalance), at=now})) "
            + "redis.call('LTRIM', KEYS[5], -" + TX_LOG_CAP + ", -1) "
            + "return 1";

    private static final String DEBIT_SCRIPT =
            "local amount = tonumber(ARGV[1]) "
            + "local balance = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "if balance < amount then "
            + "  return {0, tostring(balance)} "
            + "end "
            + "local newBalance = redis.call('INCRBYFLOAT', KEYS[1], '-' .. ARGV[1]) "
            + "redis.call('SADD', KEYS[2], ARGV[2]) "
            + "redis.call('RPUSH', KEYS[3], cjson.encode({type='debit', amount=amount, provider=ARGV[3], balance_after=tonumber(newBalance), at=tonumber(ARGV[4])})) "
            + "redis.call('LTRIM', KEYS[3], -" + TX_LOG_CAP + ", -1) "
            + "return {1, tostring(newBalance)}";

    /**
     * {@code SETNX} the idempotency marker and the balance credit happen in the same script, so a
     * StoreKit retry of an already-finished transaction can never observe "not yet redeemed" and
     * double-credit, and a crash between the two operations is impossible (there is no "between" —
     * Redis runs the whole script atomically). Returns {@code {0, currentBalance}} on an
     * already-redeemed replay (no-op, not an error, per CONTRACT.md) or {@code {1, newBalance}}
     * on a fresh credit.
     */
    private static final String REDEEM_IAP_SCRIPT =
            "local already = redis.call('SETNX', KEYS[1], '1') "
            + "if already == 0 then "
            + "  local balance = tonumber(redis.call('GET', KEYS[2]) or '0') "
            + "  return {0, tostring(balance)} "
            + "end "
            + "local newBalance = redis.call('INCRBYFLOAT', KEYS[2], ARGV[1]) "
            + "redis.call('SADD', KEYS[3], ARGV[2]) "
            + "redis.call('RPUSH', KEYS[4], cjson.encode({type='iap', amount=tonumber(ARGV[1]), product_id=ARGV[3], balance_after=tonumber(newBalance), at=tonumber(ARGV[4])})) "
            + "redis.call('LTRIM', KEYS[4], -" + TX_LOG_CAP + ", -1) "
            + "return {1, tostring(newBalance)}";

    /**
     * Takes the balance of a metered call once its real cost is known — the amount over the one
     * coin already held at the gate.
     *
     * <p>Unlike {@link #DEBIT_SCRIPT} this cannot refuse: the upstream call has already been made
     * and its response already sent, so there is nothing left to gate. It therefore takes what it
     * can and floors at zero rather than driving a wallet negative — a wallet that could not cover
     * the settlement is short by at most one call, because the gate rejects the next one. The
     * shortfall is recorded on the entry so it is visible in the ledger rather than silently
     * absorbed.
     */
    private static final String SETTLE_SCRIPT =
            "local requested = tonumber(ARGV[1]) "
            + "local balance = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "local taken = requested "
            + "if balance < requested then taken = balance end "
            + "if taken < 0 then taken = 0 end "
            + "local newBalance = balance "
            + "if taken > 0 then newBalance = tonumber(redis.call('INCRBYFLOAT', KEYS[1], '-' .. taken)) end "
            + "redis.call('SADD', KEYS[2], ARGV[2]) "
            + "redis.call('RPUSH', KEYS[3], cjson.encode({type='debit_settlement', amount=taken, "
            + "shortfall=requested - taken, provider=ARGV[3], balance_after=newBalance, at=tonumber(ARGV[4])})) "
            + "redis.call('LTRIM', KEYS[3], -" + TX_LOG_CAP + ", -1) "
            + "return tostring(taken)";

    private static final String REFUND_SCRIPT =
            "local newBalance = redis.call('INCRBYFLOAT', KEYS[1], ARGV[1]) "
            + "redis.call('SADD', KEYS[2], ARGV[2]) "
            + "redis.call('RPUSH', KEYS[3], cjson.encode({type='refund', amount=tonumber(ARGV[1]), provider=ARGV[3], balance_after=tonumber(newBalance), at=tonumber(ARGV[4])})) "
            + "redis.call('LTRIM', KEYS[3], -" + TX_LOG_CAP + ", -1) "
            + "return 'OK'";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;

    AicoinLedger(String host, int port, String password, boolean ssl) {
        this(host, port, "", password, ssl);
    }

    /**
     * @param username ACL username to authenticate as (production MemoryDB for Valkey requires
     *                  ACL username+password auth, not just a password); empty means no ACL
     *                  username — plain password-only auth (or no auth at all, if password is
     *                  also empty), exactly the prior behavior, unchanged for every local/e2e
     *                  Redis, which has no ACL configured.
     */
    AicoinLedger(String host, int port, String username, String password, boolean ssl) {
        this.client = RedisClient.create(buildRedisUri(host, port, username, password, ssl));
        this.connection = client.connect();
        this.commands = connection.async();
    }

    /** Package-visible, pure (no connection opened), so the username/password branching is unit-testable on its own. */
    static RedisURI buildRedisUri(String host, int port, String username, String password, boolean ssl) {
        RedisURI.Builder uriBuilder = RedisURI.builder().withHost(host).withPort(port).withSsl(ssl);
        if (username != null && !username.isEmpty()) {
            uriBuilder.withAuthentication(username, password != null ? password : "");
        } else if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password.toCharArray());
        }
        return uriBuilder.build();
    }

    /** {@link Optional#empty()} means the Redis call itself failed (ledger unreachable), not that the balance is unknown. */
    void getBalance(String userId, Consumer<Optional<Double>> onResult) {
        commands.get(balanceKey(userId)).whenComplete((value, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger balance lookup failed for " + userId, err);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(Optional.of(value != null ? Double.parseDouble(value) : 0.0));
        });
    }

    void claimFreeCoins(String userId, long cooldownSeconds, int poolSize, double claimAmount, Consumer<ClaimResult> onResult) {
        long nowMillis = Instant.now().toEpochMilli();
        long cooldownMillis = cooldownSeconds * 1000L;
        RedisFuture<List<Object>> future = commands.eval(CLAIM_SCRIPT, ScriptOutputType.MULTI,
                new String[] {lastClaimKey(userId), balanceKey(userId), FREE_COINS_REMAINING_KEY, KNOWN_WALLETS_KEY, txKey(userId)},
                String.valueOf(nowMillis), String.valueOf(cooldownMillis), String.valueOf(poolSize), String.valueOf(claimAmount), userId);
        future.whenComplete((raw, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger claim failed for " + userId, err);
                onResult.accept(ClaimResult.unreachable());
                return;
            }
            String reason = String.valueOf(raw.get(2));
            if ("exhausted".equals(reason)) {
                onResult.accept(ClaimResult.poolExhausted());
                return;
            }
            boolean granted = ((Number) raw.get(0)).longValue() == 1L;
            long nextEligibleAtMillis = Long.parseLong(String.valueOf(raw.get(1)));
            onResult.accept(ClaimResult.decided(granted, Instant.ofEpochMilli(nextEligibleAtMillis)));
        });
    }

    /** {@link Optional#empty()} means the lookup failed; otherwise how many free-coin claims remain in the shared pool right now. */
    void getFreeCoinsRemaining(int poolSize, Consumer<Optional<Integer>> onResult) {
        commands.get(FREE_COINS_REMAINING_KEY).whenComplete((value, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger free-coins-remaining lookup failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(Optional.of(value != null ? (int) Double.parseDouble(value) : poolSize));
        });
    }

    void transfer(String fromUserId, String toUserId, double amount, Consumer<TransferResult> onResult) {
        if (!(amount > 0)) {
            onResult.accept(TransferResult.decided(false));
            return;
        }
        long nowMillis = Instant.now().toEpochMilli();
        RedisFuture<Long> future = commands.eval(TRANSFER_SCRIPT, ScriptOutputType.INTEGER,
                new String[] {balanceKey(fromUserId), balanceKey(toUserId), KNOWN_WALLETS_KEY, txKey(fromUserId), txKey(toUserId)},
                String.valueOf(amount), fromUserId, toUserId, String.valueOf(nowMillis));
        future.whenComplete((result, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger transfer failed " + fromUserId + "->" + toUserId, err);
                onResult.accept(TransferResult.unreachable());
                return;
            }
            onResult.accept(TransferResult.decided(result != null && result == 1L));
        });
    }

    /**
     * Atomically checks and debits {@code amount} aicoin from {@code address}'s balance — the "1 aicoin is
     * worth 1 paid AI call" exchange rate is enforced here, not by the old binary "balance &gt; 0" gate.
     * Called <em>before</em> forwarding to the real provider, so the proxy never spends its own paid provider
     * key on a call a wallet can't afford; {@link #refund} reverses this if the upstream call then fails.
     */
    void debitForCall(String address, double amount, String provider, Consumer<DebitResult> onResult) {
        long nowMillis = Instant.now().toEpochMilli();
        RedisFuture<List<Object>> future = commands.eval(DEBIT_SCRIPT, ScriptOutputType.MULTI,
                new String[] {balanceKey(address), KNOWN_WALLETS_KEY, txKey(address)},
                String.valueOf(amount), address, provider, String.valueOf(nowMillis));
        future.whenComplete((raw, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger debit failed for " + address, err);
                onResult.accept(DebitResult.unreachable());
                return;
            }
            boolean success = ((Number) raw.get(0)).longValue() == 1L;
            double balance = Double.parseDouble(String.valueOf(raw.get(1)));
            onResult.accept(success ? DebitResult.success(balance) : DebitResult.insufficient(balance));
        });
    }

    /**
     * Charges the remainder of a metered call — see {@link #SETTLE_SCRIPT}. Fire-and-forget, same
     * contract as {@link #recordEvent}: the client's response has already gone out, so a ledger
     * problem here must never surface as a failed API call.
     */
    void settleCall(String address, double extraCoins, String provider) {
        if (extraCoins <= 0) {
            return;
        }
        long nowMillis = Instant.now().toEpochMilli();
        commands.eval(SETTLE_SCRIPT, ScriptOutputType.VALUE,
                new String[] {balanceKey(address), KNOWN_WALLETS_KEY, txKey(address)},
                String.valueOf(extraCoins), address, provider, String.valueOf(nowMillis))
                .exceptionally(err -> {
                    LOG.log(Level.WARNING, "ledger settlement failed for " + address, err);
                    return null;
                });
    }

    /** Reverses a {@link #debitForCall} when the upstream call it paid for didn't actually succeed. Fire-and-forget, same contract as {@link #recordEvent}. */
    void refund(String address, double amount, String provider) {
        long nowMillis = Instant.now().toEpochMilli();
        commands.eval(REFUND_SCRIPT, ScriptOutputType.STATUS,
                new String[] {balanceKey(address), KNOWN_WALLETS_KEY, txKey(address)},
                String.valueOf(amount), address, provider, String.valueOf(nowMillis))
                .exceptionally(err -> {
                    LOG.log(Level.WARNING, "ledger refund failed for " + address, err);
                    return null;
                });
    }

    /**
     * Fire-and-forget, per the old {@code EventPublisher}'s contract: must never block or fail the
     * client-facing response.
     *
     * <p>{@code walletAddress} decides only whether this event counts toward the spend budget, not
     * whether it counts toward {@code price_usd} — an internal call costs the operator exactly as
     * much as any other, so excluding it from the price signal would misprice coins. The member
     * gains a trailing {@code |i} for internal spend; the leading {@code cost|} prefix is
     * unchanged, so every event written before this existed still parses, and still reads as
     * production.
     */
    void recordEvent(String provider, double costUsd, Instant timestamp, String walletAddress) {
        recordEvent(provider, costUsd, -1, timestamp, walletAddress);
    }

    /**
     * As above, plus the call's size in tokens for the price signal to weight by. A negative
     * {@code tokens} means the response reported no usage — speech and image APIs — and is
     * recorded as absent rather than as zero, which the price formula treats differently.
     *
     * <p>The member gains a {@code |tN} field before any {@code |i}: {@code cost|uuid|t1234|i}.
     * Ordering matters because internal spend is still detected by a trailing {@code |i}, and
     * every event written before this existed keeps parsing — it simply has no size, which is
     * exactly how a usage-less response reads too.
     */
    void recordEvent(String provider, double costUsd, long tokens, Instant timestamp, String walletAddress) {
        String id = UUID.randomUUID().toString();
        String size = tokens >= 0 ? "|t" + tokens : "";
        commands.sismember(INTERNAL_WALLETS_KEY, walletAddress == null ? "" : walletAddress)
                .whenComplete((internal, err) -> {
                    if (err != null) {
                        // An unreachable set is not a reason to drop the event, and counting it as
                        // production is the safe direction: a budget that over-counts stops sales
                        // early, one that under-counts overspends real money.
                        LOG.log(Level.WARNING, "ledger internal-wallet lookup failed, recording as production", err);
                    }
                    boolean isInternal = err == null && Boolean.TRUE.equals(internal);
                    String member = costUsd + "|" + id + size + (isInternal ? "|i" : "");
                    commands.zadd(EVENTS_KEY, (double) timestamp.toEpochMilli(), member)
                            .exceptionally(zerr -> {
                                LOG.log(Level.WARNING, "ledger event record failed for provider " + provider, zerr);
                                return null;
                            });
                });
    }

    /**
     * The spend ceiling and how much production spend has run against it, per CONTRACT.md's
     * "Spend budget".
     *
     * <p>Deliberately undecayed, unlike {@link #computePrice}: a budget is cumulative cash actually
     * spent, and money does not become un-spent because it is old. Internal wallets are excluded —
     * that is the whole reason events carry the marker.
     */
    void computeBudget(Consumer<BudgetResult> onResult) {
        commands.get(BUDGET_KEY).whenComplete((budgetJson, budgetErr) -> {
            if (budgetErr != null) {
                LOG.log(Level.WARNING, "ledger budget lookup failed", budgetErr);
                onResult.accept(BudgetResult.unreachable());
                return;
            }
            Double limitUsd = parseBudgetUsd(budgetJson);
            commands.zrangeWithScores(EVENTS_KEY, 0, -1).whenComplete((entries, err) -> {
                if (err != null) {
                    LOG.log(Level.WARNING, "ledger budget spend scan failed", err);
                    onResult.accept(BudgetResult.unreachable());
                    return;
                }
                double productionSpend = 0;
                double internalSpend = 0;
                for (io.lettuce.core.ScoredValue<String> entry : entries) {
                    double cost = parseCost(entry.getValue());
                    if (parseInternal(entry.getValue())) {
                        internalSpend += cost;
                    } else {
                        productionSpend += cost;
                    }
                }
                onResult.accept(new BudgetResult(true, limitUsd, productionSpend, internalSpend));
            });
        });
    }

    /** Writes the ceiling. A null {@code usd} removes it entirely, restoring "no ceiling". */
    void setBudget(Double usd, Consumer<Boolean> onResult) {
        java.util.concurrent.CompletionStage<?> write = usd == null
                ? commands.del(BUDGET_KEY)
                : commands.set(BUDGET_KEY, "{\"usd\":" + usd + ",\"set_at\":" + Instant.now().toEpochMilli() + "}");
        write.whenComplete((reply, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger budget update failed", err);
                onResult.accept(false);
                return;
            }
            onResult.accept(true);
        });
    }

    /** Adds/removes wallet addresses whose spend is exempt from the budget. Returns the resulting membership count. */
    void updateInternalWallets(List<String> add, List<String> remove, Consumer<Optional<Long>> onResult) {
        java.util.concurrent.CompletionStage<Long> step = add.isEmpty()
                ? commands.scard(INTERNAL_WALLETS_KEY)
                : commands.sadd(INTERNAL_WALLETS_KEY, add.toArray(new String[0]));
        step.thenCompose(ignored -> remove.isEmpty()
                        ? commands.scard(INTERNAL_WALLETS_KEY)
                        : commands.srem(INTERNAL_WALLETS_KEY, remove.toArray(new String[0])))
                .thenCompose(ignored -> commands.scard(INTERNAL_WALLETS_KEY))
                .whenComplete((count, err) -> {
                    if (err != null) {
                        LOG.log(Level.WARNING, "ledger internal-wallet update failed", err);
                        onResult.accept(Optional.empty());
                        return;
                    }
                    onResult.accept(Optional.ofNullable(count));
                });
    }

    /** Every wallet currently exempt from the budget. */
    void listInternalWallets(Consumer<Optional<List<String>>> onResult) {
        commands.smembers(INTERNAL_WALLETS_KEY).whenComplete((members, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger internal-wallet list failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            List<String> sorted = new ArrayList<>(members);
            java.util.Collections.sort(sorted);
            onResult.accept(Optional.of(sorted));
        });
    }

    /** {@code {"usd":200.0,...}} → 200.0; absent/unparseable → null, i.e. no ceiling. */
    private static Double parseBudgetUsd(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"usd\"\\s*:\\s*([0-9.]+)").matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Outcome of {@link #computeBudget}. */
    static final class BudgetResult {
        private final boolean reachable;
        private final Double limitUsd;
        private final double productionSpendUsd;
        private final double internalSpendUsd;

        private BudgetResult(boolean reachable, Double limitUsd, double productionSpendUsd, double internalSpendUsd) {
            this.reachable = reachable;
            this.limitUsd = limitUsd;
            this.productionSpendUsd = productionSpendUsd;
            this.internalSpendUsd = internalSpendUsd;
        }

        static BudgetResult unreachable() {
            return new BudgetResult(false, null, 0, 0);
        }

        boolean isReachable() {
            return reachable;
        }

        /** Null means no ceiling is set — the state the proxy shipped in before budgets existed. */
        Double getLimitUsd() {
            return limitUsd;
        }

        double getProductionSpendUsd() {
            return productionSpendUsd;
        }

        double getInternalSpendUsd() {
            return internalSpendUsd;
        }

        /**
         * Whether sales must stop. An unreachable ledger is deliberately NOT exhausted: failing
         * open keeps the paywall working through a Redis blip, and the ceiling is a cost control,
         * not a correctness invariant.
         */
        boolean isExhausted() {
            return reachable && limitUsd != null && productionSpendUsd >= limitUsd;
        }
    }

    void computePrice(double halfLifeDays, Consumer<PriceResult> onResult) {
        commands.zrangeWithScores(EVENTS_KEY, 0, -1).whenComplete((entries, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger price computation failed", err);
                onResult.accept(null);
                return;
            }
            double nowMillis = Instant.now().toEpochMilli();
            List<PriceCalculator.Event> events = new ArrayList<>(entries.size());
            for (ScoredValue<String> entry : entries) {
                long tokens = parseTokens(entry.getValue());
                events.add(new PriceCalculator.Event(
                        parseCost(entry.getValue()), entry.getScore(), Math.max(tokens, 0), tokens >= 0));
            }
            onResult.accept(PriceCalculator.compute(events, nowMillis, halfLifeDays));
        });
    }

    /**
     * Reconstructs how {@code price_usd} arrived at its current value: {@code numPoints} evenly-spaced
     * samples between the earliest recorded event and now, each computed by re-running {@link
     * PriceCalculator#compute} as if {@code now} were that sample's timestamp — using, critically, only
     * the events that had actually happened by then. (Passing the *full* event list to every sample
     * would be wrong: {@link PriceCalculator#weight} clamps a negative age to a full weight of 1.0, so an
     * event from next week would incorrectly dominate a price sample from yesterday.) No events yet →
     * an empty list, not an error. {@link Optional#empty()} means the lookup itself failed.
     */
    void computePriceHistory(int numPoints, double halfLifeDays, Consumer<Optional<List<PricePoint>>> onResult) {
        commands.zrangeWithScores(EVENTS_KEY, 0, -1).whenComplete((entries, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger price-history computation failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            if (entries.isEmpty()) {
                onResult.accept(Optional.of(List.of()));
                return;
            }
            List<PriceCalculator.Event> events = new ArrayList<>(entries.size());
            double earliestMillis = Double.MAX_VALUE;
            for (ScoredValue<String> entry : entries) {
                long tokens = parseTokens(entry.getValue());
                events.add(new PriceCalculator.Event(
                        parseCost(entry.getValue()), entry.getScore(), Math.max(tokens, 0), tokens >= 0));
                earliestMillis = Math.min(earliestMillis, entry.getScore());
            }
            double nowMillis = Instant.now().toEpochMilli();
            int sampleCount = Math.max(numPoints, 2);
            List<PricePoint> points = new ArrayList<>(sampleCount);
            for (int i = 0; i < sampleCount; i++) {
                double sampleMillis = (earliestMillis == nowMillis)
                        ? nowMillis
                        : earliestMillis + (nowMillis - earliestMillis) * i / (double) (sampleCount - 1);
                List<PriceCalculator.Event> eventsSoFar = new ArrayList<>();
                for (PriceCalculator.Event event : events) {
                    if (event.timestampMillis <= sampleMillis) {
                        eventsSoFar.add(event);
                    }
                }
                double sampledPrice = PriceCalculator.compute(eventsSoFar, sampleMillis, halfLifeDays).getPriceUsd();
                points.add(new PricePoint((long) sampleMillis, sampledPrice));
            }
            onResult.accept(Optional.of(points));
        });
    }

    /** Marks every API token issued for {@code address} at or before {@code nowMillis} as revoked. */
    void revokeTokensBefore(String address, long nowMillis, Consumer<Boolean> onResult) {
        commands.set(tokenRevokedBeforeKey(address), String.valueOf(nowMillis)).whenComplete((reply, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger token revocation failed for " + address, err);
                onResult.accept(false);
                return;
            }
            onResult.accept(true);
        });
    }

    /** {@link Optional#empty()} means either the lookup failed, or no revocation has ever been recorded for this address. */
    void getTokenRevokedBefore(String address, Consumer<Optional<Long>> onResult) {
        commands.get(tokenRevokedBeforeKey(address)).whenComplete((value, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger token-revocation lookup failed for " + address, err);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(value != null ? Optional.of(Long.parseLong(value)) : Optional.empty());
        });
    }

    /**
     * Every address that has ever claimed, sent/received a transfer, or paid for a call — i.e. the
     * {@code aicoin:known-wallets} set every mutating Lua script above {@code SADD}s into. For the admin
     * page's wallet list; {@link Optional#empty()} means the lookup failed. Sorted by balance descending.
     */
    void listWalletSummaries(Consumer<Optional<List<WalletSummary>>> onResult) {
        commands.smembers(KNOWN_WALLETS_KEY).whenComplete((members, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger known-wallets lookup failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            List<String> addresses = new ArrayList<>(members);
            if (addresses.isEmpty()) {
                onResult.accept(Optional.of(List.of()));
                return;
            }
            List<CompletableFuture<WalletSummary>> futures = new ArrayList<>(addresses.size());
            for (String address : addresses) {
                CompletableFuture<String> balanceFuture = commands.get(balanceKey(address)).toCompletableFuture();
                CompletableFuture<Long> countFuture = commands.llen(txKey(address)).toCompletableFuture();
                futures.add(balanceFuture.thenCombine(countFuture, (balanceStr, count) -> new WalletSummary(
                        address, balanceStr != null ? Double.parseDouble(balanceStr) : 0.0, count.intValue())));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, joinErr) -> {
                if (joinErr != null) {
                    LOG.log(Level.WARNING, "ledger wallet-summary lookup failed", joinErr);
                    onResult.accept(Optional.empty());
                    return;
                }
                List<WalletSummary> summaries = new ArrayList<>(futures.size());
                for (CompletableFuture<WalletSummary> future : futures) {
                    summaries.add(future.join());
                }
                summaries.sort(Comparator.comparingDouble(WalletSummary::getBalance).reversed());
                onResult.accept(Optional.of(summaries));
            });
        });
    }

    /**
     * The given wallet's transaction log (claims, transfer in/out, debits, refunds), most-recent first,
     * each entry a raw JSON object string exactly as the Lua scripts above {@code cjson.encode}d it — the
     * admin page parses these client-side. {@link Optional#empty()} means the lookup failed.
     */
    /**
     * Every wallet's recent transactions merged into one newest-first feed, each entry tagged with
     * the wallet it belongs to — the admin page's "what is happening right now" view, as opposed to
     * {@link #getTransactions} which answers "what has this one wallet done".
     *
     * <p>Every paid call appears here as its {@code type=debit} entry, so this doubles as the call
     * log: provider, amount and resulting balance are already in the entry the debit script writes.
     *
     * <p>Reads at most {@code perWalletScan} entries per wallet before merging. The per-wallet logs
     * are capped ({@code TX_LOG_CAP}) and there is no global time index, so a genuinely complete
     * cross-wallet history would mean reading every log in full on every page load. Scanning a
     * bounded tail of each and then sorting gives the newest activity — which is what the page is
     * for — at a cost that grows with wallet count rather than with total history.
     *
     * @param limit          maximum entries to return after merging
     * @param perWalletScan  how many of each wallet's most recent entries to consider
     */
    void listRecentTransactions(int limit, int perWalletScan, Consumer<Optional<List<GlobalTxEntry>>> onResult) {
        commands.smembers(KNOWN_WALLETS_KEY).whenComplete((members, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger known-wallets lookup failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            List<String> addresses = new ArrayList<>(members);
            if (addresses.isEmpty()) {
                onResult.accept(Optional.of(List.of()));
                return;
            }
            List<CompletableFuture<List<GlobalTxEntry>>> futures = new ArrayList<>(addresses.size());
            for (String address : addresses) {
                futures.add(commands.lrange(txKey(address), -perWalletScan, -1).toCompletableFuture()
                        .thenApply(entries -> {
                            List<GlobalTxEntry> tagged = new ArrayList<>(entries.size());
                            for (String entry : entries) {
                                tagged.add(new GlobalTxEntry(address, entry, timestampOf(entry)));
                            }
                            return tagged;
                        }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, joinErr) -> {
                if (joinErr != null) {
                    LOG.log(Level.WARNING, "ledger cross-wallet transaction lookup failed", joinErr);
                    onResult.accept(Optional.empty());
                    return;
                }
                List<GlobalTxEntry> merged = new ArrayList<>();
                for (CompletableFuture<List<GlobalTxEntry>> future : futures) {
                    merged.addAll(future.join());
                }
                merged.sort(Comparator.comparingDouble(GlobalTxEntry::getAt).reversed());
                onResult.accept(Optional.of(merged.subList(0, Math.min(limit, merged.size()))));
            });
        });
    }

    /**
     * The {@code at} field of a transaction entry, or 0 for one that can't be parsed — an
     * unparseable entry sorts to the end of the feed rather than taking the whole page down with it.
     * Parsed with SnakeYAML, which reads JSON as a subset of YAML, the same way
     * {@link CostCalculator} reads provider usage bodies.
     */
    private static double timestampOf(String entryJson) {
        try {
            Object parsed = new org.yaml.snakeyaml.Yaml().load(entryJson);
            if (parsed instanceof Map) {
                Object at = ((Map<?, ?>) parsed).get("at");
                if (at instanceof Number) {
                    return ((Number) at).doubleValue();
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to 0 — see the doc comment.
        }
        return 0;
    }

    void getTransactions(String address, Consumer<Optional<List<String>>> onResult) {
        commands.lrange(txKey(address), 0, -1).whenComplete((entries, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger transaction-log lookup failed for " + address, err);
                onResult.accept(Optional.empty());
                return;
            }
            List<String> mostRecentFirst = new ArrayList<>(entries);
            Collections.reverse(mostRecentFirst);
            onResult.accept(Optional.of(mostRecentFirst));
        });
    }

    /**
     * The raw JSON array currently stored at {@code aicoin:iap-packages}, lazily seeded from
     * {@code seedJsonIfUnset} (the config's {@code iap.packages} list, pre-rendered to the same
     * JSON shape by {@link IapPackages#seedJson}) the first time this is ever called against a
     * fresh instance. A plain {@code SETNX} race between two concurrent first-ever readers is
     * harmless — both would try to seed the identical config-derived JSON, so whichever wins,
     * the final re-{@code GET} returns the same content either way. {@link Optional#empty()}
     * means the lookup itself failed.
     */
    void getIapPackages(String seedJsonIfUnset, Consumer<Optional<String>> onResult) {
        commands.get(IAP_PACKAGES_KEY).whenComplete((value, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger iap-packages lookup failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            if (value != null) {
                onResult.accept(Optional.of(value));
                return;
            }
            commands.setnx(IAP_PACKAGES_KEY, seedJsonIfUnset).whenComplete((seeded, seedErr) -> {
                if (seedErr != null) {
                    LOG.log(Level.WARNING, "ledger iap-packages seed failed", seedErr);
                    onResult.accept(Optional.empty());
                    return;
                }
                // Re-GET rather than trusting seedJsonIfUnset directly: a concurrent admin
                // POST /admin/iap/packages between the GET above and this SETNX would otherwise
                // be silently clobbered by whichever caller lost the SETNX race.
                commands.get(IAP_PACKAGES_KEY).whenComplete((finalValue, getErr) -> {
                    if (getErr != null) {
                        LOG.log(Level.WARNING, "ledger iap-packages post-seed lookup failed", getErr);
                        onResult.accept(Optional.empty());
                        return;
                    }
                    onResult.accept(Optional.of(finalValue != null ? finalValue : seedJsonIfUnset));
                });
            });
        });
    }

    /** Atomically overwrites {@code aicoin:iap-packages} — a plain {@code SET} is already atomic with respect to every other client, no Lua script needed for a single unconditional write. */
    void setIapPackages(String packagesJson, Consumer<Boolean> onResult) {
        commands.set(IAP_PACKAGES_KEY, packagesJson).whenComplete((reply, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger iap-packages update failed", err);
                onResult.accept(false);
                return;
            }
            onResult.accept(true);
        });
    }

    /**
     * The raw JSON object currently stored at {@code aicoin:offer} — the single coin amount every
     * app is selling right now, per CONTRACT.md's "The current offer" section. Unlike {@link
     * #getIapPackages} there is no config seed: an unset offer is a real, meaningful state (the
     * operator hasn't opened sales yet), reported as a present-but-empty {@link Optional} value
     * rather than conjured from config. {@link Optional#empty()} means the lookup itself failed.
     */
    void getOffer(Consumer<Optional<String>> onResult) {
        commands.get(OFFER_KEY).whenComplete((value, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger offer lookup failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(Optional.of(value != null ? value : ""));
        });
    }

    /** Atomically overwrites {@code aicoin:offer} — a single unconditional {@code SET}, same reasoning as {@link #setIapPackages}. */
    void setOffer(String offerJson, Consumer<Boolean> onResult) {
        commands.set(OFFER_KEY, offerJson).whenComplete((reply, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger offer update failed", err);
                onResult.accept(false);
                return;
            }
            onResult.accept(true);
        });
    }

    /**
     * Records a pinned offer under {@code offerId} with a {@code ttlSeconds} expiry — the promise
     * that {@code POST /iap/offer/check} makes to the app about to open Apple's purchase sheet,
     * honoured later by {@code POST /wallet/api/redeem-iap} even if the live offer has changed in
     * between (CONTRACT.md, "Pinning an offer across the purchase"). Redis expiry is what bounds
     * how long a pin can be hoarded, so the TTL is set atomically with the write, never after.
     */
    void putOfferPin(String offerId, String pinJson, long ttlSeconds, Consumer<Boolean> onResult) {
        commands.setex(offerPinKey(offerId), ttlSeconds, pinJson).whenComplete((reply, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger offer-pin write failed", err);
                onResult.accept(false);
                return;
            }
            onResult.accept(true);
        });
    }

    /**
     * Looks up a pinned offer. An expired or never-issued {@code offerId} is a present-but-empty
     * value, not a failure — redemption falls back to the live offer for it, which is the same
     * path a client too old to send an {@code offer_id} takes. {@link Optional#empty()} means the
     * lookup itself failed.
     */
    void getOfferPin(String offerId, Consumer<Optional<String>> onResult) {
        commands.get(offerPinKey(offerId)).whenComplete((value, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger offer-pin lookup failed", err);
                onResult.accept(Optional.empty());
                return;
            }
            onResult.accept(Optional.of(value != null ? value : ""));
        });
    }

    /**
     * Credits {@code coins} aicoin to {@code address} for a verified StoreKit2 purchase, exactly
     * once per {@code transactionId} — see {@link #REDEEM_IAP_SCRIPT}. A replay of an
     * already-redeemed {@code transactionId} is a no-op, reported via {@link RedeemResult#isFreshCredit()}
     * being {@code false}, carrying the wallet's current (unchanged) balance rather than an error.
     */
    void redeemIap(String transactionId, String address, String productId, double coins, Consumer<RedeemResult> onResult) {
        long nowMillis = Instant.now().toEpochMilli();
        RedisFuture<List<Object>> future = commands.eval(REDEEM_IAP_SCRIPT, ScriptOutputType.MULTI,
                new String[] {iapRedeemedKey(transactionId), balanceKey(address), KNOWN_WALLETS_KEY, txKey(address)},
                String.valueOf(coins), address, productId, String.valueOf(nowMillis));
        future.whenComplete((raw, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger iap redeem failed for " + address, err);
                onResult.accept(RedeemResult.unreachable());
                return;
            }
            boolean freshCredit = ((Number) raw.get(0)).longValue() == 1L;
            double balance = Double.parseDouble(String.valueOf(raw.get(1)));
            onResult.accept(RedeemResult.decided(freshCredit, balance));
        });
    }

    /**
     * Credits a completed card checkout, idempotent on Stripe's session id.
     *
     * <p>Shares {@link #REDEEM_IAP_SCRIPT}: the operation is identical — set a marker, credit the
     * balance, log the entry, all atomically — and the two paths must not drift apart in how they
     * guard against double-crediting. Only the marker key and the log's {@code product_id} field
     * differ, the latter carrying the session id so a credit can be traced back to a payment.
     */
    void creditCheckout(String sessionId, String address, double coins, Consumer<RedeemResult> onResult) {
        long nowMillis = Instant.now().toEpochMilli();
        RedisFuture<List<Object>> future = commands.eval(REDEEM_IAP_SCRIPT, ScriptOutputType.MULTI,
                new String[] {checkoutCreditedKey(sessionId), balanceKey(address), KNOWN_WALLETS_KEY, txKey(address)},
                String.valueOf(coins), address, "checkout:" + sessionId, String.valueOf(nowMillis));
        future.whenComplete((raw, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger checkout credit failed for " + address, err);
                onResult.accept(RedeemResult.unreachable());
                return;
            }
            boolean freshCredit = ((Number) raw.get(0)).longValue() == 1L;
            double balance = Double.parseDouble(String.valueOf(raw.get(1)));
            onResult.accept(RedeemResult.decided(freshCredit, balance));
        });
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    private static String balanceKey(String userId) {
        return "aicoin:" + TAG + ":balance:" + userId;
    }

    private static String lastClaimKey(String userId) {
        return "aicoin:" + TAG + ":lastclaim:" + userId;
    }

    private static String tokenRevokedBeforeKey(String address) {
        return "aicoin:" + TAG + ":token-revoked-before:" + address;
    }

    private static String txKey(String address) {
        return "aicoin:" + TAG + ":tx:" + address;
    }

    private static String iapRedeemedKey(String transactionId) {
        return "aicoin:" + TAG + ":iap-redeemed:" + transactionId;
    }

    /**
     * Marker for a credited card checkout, keyed by Stripe's session id. Stripe retries a webhook
     * until it gets a 2xx — for hours — so a delivery that succeeded but whose response was lost
     * arrives again, and this is what keeps the second one from paying out twice.
     */
    private static String checkoutCreditedKey(String sessionId) {
        return "aicoin:" + TAG + ":checkout-credited:" + sessionId;
    }

    private static String offerPinKey(String offerId) {
        return "aicoin:" + TAG + ":offer-pin:" + offerId;
    }

    private static double parseCost(String member) {
        int sep = member.indexOf('|');
        return Double.parseDouble(sep >= 0 ? member.substring(0, sep) : member);
    }

    /** {@code cost|uuid|i} marks spend by an internal wallet — see {@link #recordEvent}. Anything else is production. */
    private static boolean parseInternal(String member) {
        return member.endsWith("|i");
    }

    /**
     * The {@code |tN} size field, or {@code -1} when the member carries none — an event from
     * before sizes were recorded, or a response that reported no usage. Both mean the same thing
     * to the price formula: this call has no size to weight by.
     */
    private static long parseTokens(String member) {
        for (String field : member.split("\\|")) {
            if (field.length() > 1 && field.charAt(0) == 't') {
                try {
                    return Long.parseLong(field.substring(1));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** Outcome of {@link #claimFreeCoins}: ledger-unreachable, a decided grant/cooldown-reject with the resulting deadline, or the shared pool being exhausted. */
    static final class ClaimResult {
        private final boolean reachable;
        private final boolean granted;
        private final boolean poolExhausted;
        private final Instant nextEligibleAt;

        private ClaimResult(boolean reachable, boolean granted, boolean poolExhausted, Instant nextEligibleAt) {
            this.reachable = reachable;
            this.granted = granted;
            this.poolExhausted = poolExhausted;
            this.nextEligibleAt = nextEligibleAt;
        }

        static ClaimResult unreachable() {
            return new ClaimResult(false, false, false, null);
        }

        static ClaimResult decided(boolean granted, Instant nextEligibleAt) {
            return new ClaimResult(true, granted, false, nextEligibleAt);
        }

        static ClaimResult poolExhausted() {
            return new ClaimResult(true, false, true, null);
        }

        boolean isReachable() {
            return reachable;
        }

        boolean isGranted() {
            return granted;
        }

        /** True when the claim was rejected because the shared free-coins pool is at zero, not because of the per-wallet cooldown. */
        boolean isPoolExhausted() {
            return poolExhausted;
        }

        Instant getNextEligibleAt() {
            return nextEligibleAt;
        }
    }

    /** Outcome of {@link #debitForCall}: ledger-unreachable, or a decided success/insufficient-balance result, either way carrying the resulting/current balance. */
    static final class DebitResult {
        private final boolean reachable;
        private final boolean success;
        private final double balance;

        private DebitResult(boolean reachable, boolean success, double balance) {
            this.reachable = reachable;
            this.success = success;
            this.balance = balance;
        }

        static DebitResult unreachable() {
            return new DebitResult(false, false, 0);
        }

        static DebitResult success(double balance) {
            return new DebitResult(true, true, balance);
        }

        static DebitResult insufficient(double balance) {
            return new DebitResult(true, false, balance);
        }

        boolean isReachable() {
            return reachable;
        }

        boolean isSuccess() {
            return success;
        }

        double getBalance() {
            return balance;
        }
    }

    /** Outcome of {@link #redeemIap}: ledger-unreachable, or a decided fresh-credit/already-redeemed-replay result, either way carrying the resulting/current balance. */
    static final class RedeemResult {
        private final boolean reachable;
        private final boolean freshCredit;
        private final double balance;

        private RedeemResult(boolean reachable, boolean freshCredit, double balance) {
            this.reachable = reachable;
            this.freshCredit = freshCredit;
            this.balance = balance;
        }

        static RedeemResult unreachable() {
            return new RedeemResult(false, false, 0);
        }

        static RedeemResult decided(boolean freshCredit, double balance) {
            return new RedeemResult(true, freshCredit, balance);
        }

        boolean isReachable() {
            return reachable;
        }

        /** False for an already-redeemed {@code transactionId} replay — a safe no-op, not an error, per CONTRACT.md. */
        boolean isFreshCredit() {
            return freshCredit;
        }

        double getBalance() {
            return balance;
        }
    }

    /** Outcome of {@link #transfer}: ledger-unreachable, or a decided success/insufficient-balance result. */
    static final class TransferResult {
        private final boolean reachable;
        private final boolean success;

        private TransferResult(boolean reachable, boolean success) {
            this.reachable = reachable;
            this.success = success;
        }

        static TransferResult unreachable() {
            return new TransferResult(false, false);
        }

        static TransferResult decided(boolean success) {
            return new TransferResult(true, success);
        }

        boolean isReachable() {
            return reachable;
        }

        boolean isSuccess() {
            return success;
        }
    }

    /** Mirrors the old Go node's {@code GET /price} shape, minus the chain-specific {@code height} field. */
    static final class PriceResult {
        private final double priceUsd;
        private final double totalSpendUsd;
        private final double weightedTotal;
        private final double halfLifeDays;

        PriceResult(double priceUsd, double totalSpendUsd, double weightedTotal, double halfLifeDays) {
            this.priceUsd = priceUsd;
            this.totalSpendUsd = totalSpendUsd;
            this.weightedTotal = weightedTotal;
            this.halfLifeDays = halfLifeDays;
        }

        double getPriceUsd() {
            return priceUsd;
        }

        double getTotalSpendUsd() {
            return totalSpendUsd;
        }

        double getWeightedTotal() {
            return weightedTotal;
        }

        double getHalfLifeDays() {
            return halfLifeDays;
        }
    }

    /** One row of the admin page's wallet list: a known address, its current balance, and how many transaction-log entries it has. */
    /** One wallet's transaction, tagged with whose it is — see {@link #listRecentTransactions}. */
    static final class GlobalTxEntry {
        private final String address;
        private final String json;
        private final double at;

        GlobalTxEntry(String address, String json, double at) {
            this.address = address;
            this.json = json;
            this.at = at;
        }

        String getAddress() {
            return address;
        }

        /** The entry exactly as the Lua scripts {@code cjson.encode}d it. */
        String getJson() {
            return json;
        }

        double getAt() {
            return at;
        }
    }

    static final class WalletSummary {
        private final String address;
        private final double balance;
        private final int transactionCount;

        WalletSummary(String address, double balance, int transactionCount) {
            this.address = address;
            this.balance = balance;
            this.transactionCount = transactionCount;
        }

        String getAddress() {
            return address;
        }

        double getBalance() {
            return balance;
        }

        int getTransactionCount() {
            return transactionCount;
        }
    }

    /** One sample in {@link #computePriceHistory}'s reconstructed series: what {@code price_usd} was as of {@code atMillis}. */
    static final class PricePoint {
        private final long atMillis;
        private final double priceUsd;

        PricePoint(long atMillis, double priceUsd) {
            this.atMillis = atMillis;
            this.priceUsd = priceUsd;
        }

        long getAtMillis() {
            return atMillis;
        }

        double getPriceUsd() {
            return priceUsd;
        }
    }
}
