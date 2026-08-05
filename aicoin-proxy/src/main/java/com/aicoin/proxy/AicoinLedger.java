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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
    private static final String EVENTS_KEY = "aicoin:events";
    private static final String FREE_COINS_REMAINING_KEY = "aicoin:free-coins-remaining";

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
            + "redis.call('INCRBYFLOAT', KEYS[2], ARGV[4]) "
            + "redis.call('SET', KEYS[3], remaining - amount) "
            + "return {1, tostring(now + cooldown), 'granted'}";

    private static final String TRANSFER_SCRIPT =
            "local amount = tonumber(ARGV[1]) "
            + "local from = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "if amount <= 0 or from < amount then "
            + "  return 0 "
            + "end "
            + "redis.call('INCRBYFLOAT', KEYS[1], '-' .. ARGV[1]) "
            + "redis.call('INCRBYFLOAT', KEYS[2], ARGV[1]) "
            + "return 1";

    private static final String DEBIT_SCRIPT =
            "local amount = tonumber(ARGV[1]) "
            + "local balance = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "if balance < amount then "
            + "  return {0, tostring(balance)} "
            + "end "
            + "redis.call('INCRBYFLOAT', KEYS[1], '-' .. ARGV[1]) "
            + "return {1, tostring(balance - amount)}";

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final RedisAsyncCommands<String, String> commands;

    AicoinLedger(String host, int port, String password, boolean ssl) {
        RedisURI.Builder uriBuilder = RedisURI.builder().withHost(host).withPort(port).withSsl(ssl);
        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password.toCharArray());
        }
        this.client = RedisClient.create(uriBuilder.build());
        this.connection = client.connect();
        this.commands = connection.async();
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
                new String[] {lastClaimKey(userId), balanceKey(userId), FREE_COINS_REMAINING_KEY},
                String.valueOf(nowMillis), String.valueOf(cooldownMillis), String.valueOf(poolSize), String.valueOf(claimAmount));
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
        RedisFuture<Long> future = commands.eval(TRANSFER_SCRIPT, ScriptOutputType.INTEGER,
                new String[] {balanceKey(fromUserId), balanceKey(toUserId)}, String.valueOf(amount));
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
    void debitForCall(String address, double amount, Consumer<DebitResult> onResult) {
        RedisFuture<List<Object>> future = commands.eval(DEBIT_SCRIPT, ScriptOutputType.MULTI,
                new String[] {balanceKey(address)}, String.valueOf(amount));
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

    /** Reverses a {@link #debitForCall} when the upstream call it paid for didn't actually succeed. Fire-and-forget, same contract as {@link #recordEvent}. */
    void refund(String address, double amount) {
        commands.incrbyfloat(balanceKey(address), amount).exceptionally(err -> {
            LOG.log(Level.WARNING, "ledger refund failed for " + address, err);
            return null;
        });
    }

    /** Fire-and-forget, per the old {@code EventPublisher}'s contract: must never block or fail the client-facing response. */
    void recordEvent(String provider, double costUsd, Instant timestamp) {
        String member = costUsd + "|" + UUID.randomUUID();
        commands.zadd(EVENTS_KEY, (double) timestamp.toEpochMilli(), member)
                .exceptionally(err -> {
                    LOG.log(Level.WARNING, "ledger event record failed for provider " + provider, err);
                    return null;
                });
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
                events.add(new PriceCalculator.Event(parseCost(entry.getValue()), entry.getScore()));
            }
            onResult.accept(PriceCalculator.compute(events, nowMillis, halfLifeDays));
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

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }

    private static String balanceKey(String userId) {
        return "aicoin:balance:" + userId;
    }

    private static String lastClaimKey(String userId) {
        return "aicoin:lastclaim:" + userId;
    }

    private static String tokenRevokedBeforeKey(String address) {
        return "aicoin:token-revoked-before:" + address;
    }

    private static double parseCost(String member) {
        int sep = member.indexOf('|');
        return Double.parseDouble(sep >= 0 ? member.substring(0, sep) : member);
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
}
