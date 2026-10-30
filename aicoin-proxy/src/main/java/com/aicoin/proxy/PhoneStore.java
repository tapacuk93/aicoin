package com.aicoin.proxy;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Where hosted phones are recorded, and the one place their minutes are charged — see
 * {@link HostedPhones}.
 *
 * <p>Durable because a phone outlives the process that started it: a redeploy must neither forget
 * a running instance (it would bill the operator with nobody paying) nor charge a minute twice.
 * So the record holds the launch time and how many minutes have been charged, and a charge is one
 * atomic step that debits the wallet and advances that count together — after a restart the meter
 * charges exactly the minutes that started while it was down, and none again.
 *
 * <p>In the ledger's Redis under the ledger's {@code {aicoin}} hash tag, because a charge touches
 * the wallet's balance and transaction log in the same script as the phone record, and MemoryDB
 * (always cluster mode) refuses a script whose keys span slots:
 * <ul>
 *   <li>{@code aicoin:{aicoin}:phone:<wallet>} — a hash, the wallet's one phone;</li>
 *   <li>{@code aicoin:{aicoin}:phones} — the set of wallets that have one, which the meter walks;</li>
 *   <li>{@code aicoin:{aicoin}:phone-meter-lease} — which process runs the meter.</li>
 * </ul>
 *
 * <p>Synchronous on purpose: every caller runs on {@link HostedPhones}' own thread, never on a
 * Netty event loop.
 */
interface PhoneStore {

    String INDEX_KEY = "aicoin:" + AicoinLedger.TAG + ":phones";
    String LEASE_KEY = "aicoin:" + AicoinLedger.TAG + ":phone-meter-lease";

    static String key(String wallet) {
        return "aicoin:" + AicoinLedger.TAG + ":phone:" + wallet;
    }

    /** What one charge came to. */
    record Charge(boolean found, long minutes, long coins, double balance) {
        static Charge missing() {
            return new Charge(false, 0, 0, 0);
        }
    }

    /** The wallet's phone, or null. */
    PhoneRecord get(String wallet);

    /** Records a new phone. False when the wallet already has one. */
    boolean create(PhoneRecord record);

    /**
     * Writes the record's state, instance, host and warning fields, if nothing else wrote it since
     * it was read ({@code record.version}). On success the record's version is advanced. Never
     * writes the charged minutes or coins: only {@link #charge} does.
     */
    boolean save(PhoneRecord record);

    /**
     * Charges the wallet for every minute up to {@code minutesTo} not yet charged for this phone,
     * at the phone's rate, and records them on the phone — atomically. A negative balance is
     * allowed: the instance ran, so the minute is owed (the ledger's settle rule). Returns
     * {@code found=false} when the wallet has no phone with this id.
     */
    Charge charge(String wallet, String phoneId, long minutesTo, long nowMillis);

    /** Removes the phone if it is still this one. */
    boolean delete(String wallet, String phoneId);

    /** Wallets the index says have a phone (a stale entry is possible; {@link #forget} it). */
    List<String> wallets();

    void forget(String wallet);

    double balance(String wallet);

    /** Takes or renews the meter lease for {@code owner}. False while another process holds it. */
    boolean lease(String owner, long millis);

    /** The ledger's Redis. */
    final class Redis implements PhoneStore {

        private static final long TIMEOUT_SECONDS = 10;

        private static final String CREATE_SCRIPT =
                "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end "
                + "for i = 1, #ARGV, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) end "
                + "redis.call('SADD', KEYS[2], ARGV[2]) "
                + "return 1";

        private static final String SAVE_SCRIPT =
                "local v = redis.call('HGET', KEYS[1], 'version') "
                + "if v == false or v ~= ARGV[1] then return 0 end "
                + "for i = 2, #ARGV, 2 do redis.call('HSET', KEYS[1], ARGV[i], ARGV[i + 1]) end "
                + "redis.call('HINCRBY', KEYS[1], 'version', 1) "
                + "return 1";

        /**
         * KEYS: phone, balance, known wallets, tx log. ARGV: phoneId, minutesTo, now, wallet.
         * Returns {found, minutes, coins, balance}.
         */
        private static final String CHARGE_SCRIPT =
                "local id = redis.call('HGET', KEYS[1], 'phoneId') "
                + "if id == false or id ~= ARGV[1] then return {0, 0, 0, '0'} end "
                + "local billed = tonumber(redis.call('HGET', KEYS[1], 'billedMinutes') or '0') "
                + "local cpm = tonumber(redis.call('HGET', KEYS[1], 'coinsPerMinute') or '0') "
                + "local due = tonumber(ARGV[2]) - billed "
                + "if due <= 0 or cpm <= 0 then "
                + "  return {1, 0, 0, tostring(redis.call('GET', KEYS[2]) or '0')} "
                + "end "
                + "local amount = due * cpm "
                + "local balance = redis.call('INCRBYFLOAT', KEYS[2], '-' .. amount) "
                + "redis.call('HINCRBY', KEYS[1], 'billedMinutes', due) "
                + "redis.call('HINCRBY', KEYS[1], 'chargedCoins', amount) "
                + "redis.call('HINCRBY', KEYS[1], 'version', 1) "
                + "redis.call('SADD', KEYS[3], ARGV[4]) "
                + "redis.call('RPUSH', KEYS[4], cjson.encode({type='debit', amount=amount, provider='anyphone', "
                + "phone_id=ARGV[1], minutes=due, balance_after=tonumber(balance), at=tonumber(ARGV[3])})) "
                + "redis.call('LTRIM', KEYS[4], -" + AicoinLedger.TX_LOG_CAP + ", -1) "
                + "return {1, due, amount, tostring(balance)}";

        private static final String DELETE_SCRIPT =
                "if redis.call('HGET', KEYS[1], 'phoneId') ~= ARGV[1] then return 0 end "
                + "redis.call('DEL', KEYS[1]) "
                + "redis.call('SREM', KEYS[2], ARGV[2]) "
                + "return 1";

        private static final String LEASE_SCRIPT =
                "if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end "
                + "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('PEXPIRE', KEYS[1], ARGV[2]) return 1 end "
                + "return 0";

        private final RedisAsyncCommands<String, String> redis;

        Redis(RedisAsyncCommands<String, String> redis) {
            this.redis = redis;
        }

        private static <T> T await(RedisFuture<T> future) {
            try {
                return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for Redis", e);
            } catch (Exception e) {
                throw new IllegalStateException("Redis call failed: " + e.getMessage(), e);
            }
        }

        @Override
        public PhoneRecord get(String wallet) {
            Map<String, String> fields = await(redis.hgetall(key(wallet)));
            return fields == null || fields.isEmpty() ? null : PhoneRecord.fromFields(fields);
        }

        @Override
        public boolean create(PhoneRecord record) {
            record.version = 0;
            Map<String, String> fields = record.toFields();
            // The wallet first, so the script can add ARGV[2] to the index.
            List<String> args = new ArrayList<>();
            args.add("wallet");
            args.add(record.wallet);
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (!e.getKey().equals("wallet")) {
                    args.add(e.getKey());
                    args.add(e.getValue());
                }
            }
            Long created = await(redis.eval(CREATE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[] {key(record.wallet), INDEX_KEY}, args.toArray(new String[0])));
            return created != null && created == 1L;
        }

        @Override
        public boolean save(PhoneRecord record) {
            List<String> args = new ArrayList<>();
            args.add(String.valueOf(record.version));
            for (Map.Entry<String, String> e : record.mutableFields().entrySet()) {
                args.add(e.getKey());
                args.add(e.getValue());
            }
            Long saved = await(redis.eval(SAVE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[] {key(record.wallet)}, args.toArray(new String[0])));
            if (saved != null && saved == 1L) {
                record.version++;
                return true;
            }
            return false;
        }

        @Override
        public Charge charge(String wallet, String phoneId, long minutesTo, long nowMillis) {
            List<Object> raw = await(redis.eval(CHARGE_SCRIPT, ScriptOutputType.MULTI,
                    new String[] {key(wallet), AicoinLedger.balanceKey(wallet), AicoinLedger.KNOWN_WALLETS_KEY,
                            AicoinLedger.txKey(wallet)},
                    phoneId, String.valueOf(minutesTo), String.valueOf(nowMillis), wallet));
            if (((Number) raw.get(0)).longValue() != 1L) {
                return Charge.missing();
            }
            return new Charge(true, ((Number) raw.get(1)).longValue(), ((Number) raw.get(2)).longValue(),
                    Double.parseDouble(String.valueOf(raw.get(3))));
        }

        @Override
        public boolean delete(String wallet, String phoneId) {
            Long deleted = await(redis.eval(DELETE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[] {key(wallet), INDEX_KEY}, phoneId, wallet));
            return deleted != null && deleted == 1L;
        }

        @Override
        public List<String> wallets() {
            return new ArrayList<>(await(redis.smembers(INDEX_KEY)));
        }

        @Override
        public void forget(String wallet) {
            await(redis.srem(INDEX_KEY, wallet));
        }

        @Override
        public double balance(String wallet) {
            String value = await(redis.get(AicoinLedger.balanceKey(wallet)));
            return value == null ? 0.0 : Double.parseDouble(value);
        }

        @Override
        public boolean lease(String owner, long millis) {
            Long taken = await(redis.eval(LEASE_SCRIPT, ScriptOutputType.INTEGER,
                    new String[] {LEASE_KEY}, owner, String.valueOf(millis)));
            return taken != null && taken == 1L;
        }
    }

    /** For tests: the same semantics in memory, with balances of its own. */
    final class Memory implements PhoneStore {
        final Map<String, PhoneRecord> phones = new LinkedHashMap<>();
        final Map<String, Double> balances = new HashMap<>();
        final List<String> transactions = new ArrayList<>();
        String leaseOwner;
        long leaseUntil;
        /** A test's clock for the lease. */
        java.util.function.LongSupplier clock = System::currentTimeMillis;

        @Override
        public synchronized PhoneRecord get(String wallet) {
            PhoneRecord stored = phones.get(wallet);
            return stored == null ? null : stored.copy();
        }

        @Override
        public synchronized boolean create(PhoneRecord record) {
            if (phones.containsKey(record.wallet)) {
                return false;
            }
            record.version = 0;
            phones.put(record.wallet, record.copy());
            return true;
        }

        @Override
        public synchronized boolean save(PhoneRecord record) {
            PhoneRecord stored = phones.get(record.wallet);
            if (stored == null || stored.version != record.version) {
                return false;
            }
            PhoneRecord next = stored.copy();
            next.state = record.state;
            next.instanceId = record.instanceId;
            next.host = record.host;
            next.warnedAt = record.warnedAt;
            next.exhaustedAt = record.exhaustedAt;
            next.version = stored.version + 1;
            phones.put(record.wallet, next);
            record.version++;
            return true;
        }

        @Override
        public synchronized Charge charge(String wallet, String phoneId, long minutesTo, long nowMillis) {
            PhoneRecord stored = phones.get(wallet);
            if (stored == null || !stored.phoneId.equals(phoneId)) {
                return Charge.missing();
            }
            long due = minutesTo - stored.billedMinutes;
            if (due <= 0 || stored.coinsPerMinute <= 0) {
                return new Charge(true, 0, 0, balances.getOrDefault(wallet, 0.0));
            }
            long amount = due * stored.coinsPerMinute;
            double balance = balances.getOrDefault(wallet, 0.0) - amount;
            balances.put(wallet, balance);
            stored.billedMinutes += due;
            stored.chargedCoins += amount;
            stored.version++;
            transactions.add(wallet + " " + phoneId + " " + due + "min " + amount);
            return new Charge(true, due, amount, balance);
        }

        @Override
        public synchronized boolean delete(String wallet, String phoneId) {
            PhoneRecord stored = phones.get(wallet);
            if (stored == null || !stored.phoneId.equals(phoneId)) {
                return false;
            }
            phones.remove(wallet);
            return true;
        }

        @Override
        public synchronized List<String> wallets() {
            return new ArrayList<>(phones.keySet());
        }

        @Override
        public synchronized void forget(String wallet) {
        }

        @Override
        public synchronized double balance(String wallet) {
            return balances.getOrDefault(wallet, 0.0);
        }

        @Override
        public synchronized boolean lease(String owner, long millis) {
            long now = clock.getAsLong();
            if (leaseOwner == null || leaseOwner.equals(owner) || now >= leaseUntil) {
                leaseOwner = owner;
                leaseUntil = now + millis;
                return true;
            }
            return false;
        }
    }
}
