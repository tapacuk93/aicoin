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

    private static final String CLAIM_SCRIPT =
            "local now = tonumber(ARGV[1]) "
            + "local cooldown = tonumber(ARGV[2]) "
            + "local last = redis.call('GET', KEYS[1]) "
            + "if last and (now - tonumber(last)) < cooldown then "
            + "  return {0, tostring(tonumber(last) + cooldown)} "
            + "end "
            + "redis.call('SET', KEYS[1], ARGV[1]) "
            + "redis.call('INCRBYFLOAT', KEYS[2], '1.0') "
            + "return {1, tostring(now + cooldown)}";

    private static final String TRANSFER_SCRIPT =
            "local amount = tonumber(ARGV[1]) "
            + "local from = tonumber(redis.call('GET', KEYS[1]) or '0') "
            + "if amount <= 0 or from < amount then "
            + "  return 0 "
            + "end "
            + "redis.call('INCRBYFLOAT', KEYS[1], '-' .. ARGV[1]) "
            + "redis.call('INCRBYFLOAT', KEYS[2], ARGV[1]) "
            + "return 1";

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

    void claimFreeCoins(String userId, long cooldownSeconds, Consumer<ClaimResult> onResult) {
        long nowMillis = Instant.now().toEpochMilli();
        long cooldownMillis = cooldownSeconds * 1000L;
        RedisFuture<List<Object>> future = commands.eval(CLAIM_SCRIPT, ScriptOutputType.MULTI,
                new String[] {lastClaimKey(userId), balanceKey(userId)},
                String.valueOf(nowMillis), String.valueOf(cooldownMillis));
        future.whenComplete((raw, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "ledger claim failed for " + userId, err);
                onResult.accept(ClaimResult.unreachable());
                return;
            }
            boolean granted = ((Number) raw.get(0)).longValue() == 1L;
            long nextEligibleAtMillis = Long.parseLong(String.valueOf(raw.get(1)));
            onResult.accept(ClaimResult.decided(granted, Instant.ofEpochMilli(nextEligibleAtMillis)));
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

    private static double parseCost(String member) {
        int sep = member.indexOf('|');
        return Double.parseDouble(sep >= 0 ? member.substring(0, sep) : member);
    }

    /** Outcome of {@link #claimFreeCoins}: ledger-unreachable, or a decided grant/reject with the resulting cooldown deadline. */
    static final class ClaimResult {
        private final boolean reachable;
        private final boolean granted;
        private final Instant nextEligibleAt;

        private ClaimResult(boolean reachable, boolean granted, Instant nextEligibleAt) {
            this.reachable = reachable;
            this.granted = granted;
            this.nextEligibleAt = nextEligibleAt;
        }

        static ClaimResult unreachable() {
            return new ClaimResult(false, false, null);
        }

        static ClaimResult decided(boolean granted, Instant nextEligibleAt) {
            return new ClaimResult(true, granted, nextEligibleAt);
        }

        boolean isReachable() {
            return reachable;
        }

        boolean isGranted() {
            return granted;
        }

        Instant getNextEligibleAt() {
            return nextEligibleAt;
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
