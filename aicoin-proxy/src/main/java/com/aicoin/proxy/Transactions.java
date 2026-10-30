package com.aicoin.proxy;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The transaction log: one entry per paid call — which wallet, which app, what for, which
 * provider and model, whether it worked, and what it was charged in coins and in dollars — for
 * {@code GET /admin/transactions} and the admin app's Transactions tab.
 *
 * <p><b>Labels are optional and change nothing.</b> An app names itself with {@code X-AICoin-App}
 * and the call's purpose with {@code X-AICoin-Purpose}. Neither is required, neither changes
 * pricing, auth, limits or the response, and every client built before they existed keeps working
 * exactly as before: its calls are recorded with purpose {@code unspecified} and with the app its
 * wallet is assigned to ({@link AppCaps}), or {@code unknown}.
 *
 * <p><b>Recording is best effort and off the call path.</b> {@link #record} returns at once; the
 * Redis writes run asynchronously, and a failure is logged and dropped. Nothing here can fail or
 * delay a call.
 *
 * <p><b>Bounded.</b> Entries live in one sorted set scored by time; each write trims entries older
 * than the retention window and beyond the entry cap. Totals come from per-day aggregate hashes
 * that expire with the window, so totals for app / purpose / ok filters cover the whole window
 * even when the entry cap has trimmed the oldest entries; a wallet filter is totalled from the
 * entries still kept.
 */
final class Transactions {

    private static final Logger LOG = Logger.getLogger(Transactions.class.getName());

    static final String PURPOSE_HEADER = "X-AICoin-Purpose";
    static final String UNSPECIFIED = "unspecified";
    static final String UNKNOWN = "unknown";
    static final int MAX_LABEL = 64;
    static final int DEFAULT_RETENTION_DAYS = 90;
    static final int DEFAULT_MAX_ENTRIES = 100_000;
    static final int MAX_PAGE = 200;
    static final int DEFAULT_PAGE = 50;
    /** Entries read per page request at most, however few match its filters. */
    static final int SCAN_CAP = 5_000;
    private static final int BATCH = 500;

    // ------------------------------------------------------------------------------------------
    // The labels a request carries
    // ------------------------------------------------------------------------------------------

    /** The app and purpose a request named, each null when it named none. */
    static final class Tag {
        static final Tag NONE = new Tag(null, null);
        static final AttributeKey<Tag> KEY = AttributeKey.valueOf("aicoin.transactionTag");

        private final String app;
        private final String purpose;

        Tag(String app, String purpose) {
            this.app = app;
            this.purpose = purpose;
        }

        static Tag fromHeaders(String appHeader, String purposeHeader) {
            String app = label(appHeader);
            String purpose = label(purposeHeader);
            return app == null && purpose == null ? NONE : new Tag(app, purpose);
        }

        String app() {
            return app;
        }

        String purpose() {
            return purpose;
        }

        /** The tag of the request currently being served on {@code channel}, or {@link #NONE}. */
        static Tag of(Channel channel) {
            if (channel == null) {
                return NONE;
            }
            Tag tag = channel.attr(KEY).get();
            return tag == null ? NONE : tag;
        }

        static void set(Channel channel, Tag tag) {
            channel.attr(KEY).set(tag);
        }
    }

    /**
     * A header value as a label: printable ASCII only, whitespace collapsed, lowercase, at most
     * {@link #MAX_LABEL} characters. Null when nothing is left. Lowercase so that "Chat" and "chat"
     * are one purpose, and so a bundle id matches the app names {@link AppCaps} uses.
     */
    static String label(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LABEL));
        boolean space = false;
        for (int i = 0; i < raw.length() && sb.length() < MAX_LABEL; i++) {
            char c = raw.charAt(i);
            if (c == ' ' || c == '\t') {
                space = sb.length() > 0;
                continue;
            }
            if (c < 0x21 || c > 0x7E || c == '|') {
                continue;
            }
            if (space && sb.length() < MAX_LABEL - 1) {
                sb.append(' ');
            }
            space = false;
            sb.append(Character.toLowerCase(c));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static final Pattern BODY_MODEL = Pattern.compile("\"model(?:_id)?\"\\s*:\\s*\"([^\"\\\\]{1,100})\"");
    private static final Pattern PATH_MODEL = Pattern.compile("/models/([^/:?]{1,100})");
    private static final int MODEL_SCAN_BYTES = 64 * 1024;

    /** The model a provider request names — in its body ({@code model}, {@code model_id}) or its path (Gemini). */
    static String modelOf(String path, byte[] body) {
        if (body != null && body.length > 0) {
            String head = new String(body, 0, Math.min(body.length, MODEL_SCAN_BYTES), StandardCharsets.UTF_8);
            Matcher m = BODY_MODEL.matcher(head);
            if (m.find()) {
                return label(m.group(1));
            }
        }
        if (path != null) {
            Matcher m = PATH_MODEL.matcher(path);
            if (m.find()) {
                return label(m.group(1));
            }
        }
        return null;
    }

    private static final Pattern ERROR_MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"([^\"\\\\]{1,160})");

    /** A short reason for a provider's non-2xx answer: its status and, when it says one, its message. */
    static String upstreamError(int status, byte[] body) {
        String reason = "HTTP " + status;
        if (body != null && body.length > 0) {
            Matcher m = ERROR_MESSAGE.matcher(new String(body, 0, Math.min(body.length, 4096), StandardCharsets.UTF_8));
            if (m.find()) {
                reason += ": " + m.group(1);
            }
        }
        return shortError(reason);
    }

    static String shortError(String error) {
        if (error == null) {
            return null;
        }
        String flat = error.replaceAll("[\\p{Cntrl}|]+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    /** The path a request was sent to, without its query (which may carry a provider key). */
    static String endpointOf(String uri) {
        if (uri == null) {
            return null;
        }
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        return path.length() > 120 ? path.substring(0, 120) : path;
    }

    // ------------------------------------------------------------------------------------------
    // One recorded call
    // ------------------------------------------------------------------------------------------

    /** What happened on one paid call, as the call path knows it. */
    static final class Call {
        String wallet;
        Tag tag = Tag.NONE;
        String provider;
        String model;
        String endpoint;
        boolean ok;
        int status;
        String error;
        long coins;
        double costUsd;

        static Call of(String wallet, Tag tag, String provider) {
            Call c = new Call();
            c.wallet = wallet;
            c.tag = tag == null ? Tag.NONE : tag;
            c.provider = provider;
            return c;
        }

        Call model(String model) {
            this.model = model;
            return this;
        }

        Call endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        Call succeeded(int status, long coins, double costUsd) {
            this.ok = true;
            this.status = status;
            this.coins = coins;
            this.costUsd = costUsd;
            return this;
        }

        Call failed(int status, String error) {
            this.ok = false;
            this.status = status;
            this.error = shortError(error);
            return this;
        }
    }

    /** A stored entry. */
    static final class Entry {
        long score;
        String id;
        long atMs;
        String wallet;
        String app;
        String purpose;
        String provider;
        String model;
        String endpoint;
        boolean ok;
        int status;
        long coins;
        double usd;
        double costUsd;
        String error;

        /** Tab-separated, versioned: a fifth of the size JSON would be, at up to 100k entries. */
        String encode() {
            return String.join("\t", "1", id, Long.toString(atMs), nz(wallet), nz(app), nz(purpose), nz(provider),
                    nz(model), nz(endpoint), ok ? "1" : "0", Integer.toString(status), Long.toString(coins),
                    Double.toString(usd), Double.toString(costUsd), nz(error));
        }

        static Entry decode(double score, String member) {
            String[] f = member.split("\t", -1);
            if (f.length < 15 || !"1".equals(f[0])) {
                return null;
            }
            try {
                Entry e = new Entry();
                e.score = (long) score;
                e.id = f[1];
                e.atMs = Long.parseLong(f[2]);
                e.wallet = nn(f[3]);
                e.app = nn(f[4]);
                e.purpose = nn(f[5]);
                e.provider = nn(f[6]);
                e.model = nn(f[7]);
                e.endpoint = nn(f[8]);
                e.ok = "1".equals(f[9]);
                e.status = Integer.parseInt(f[10]);
                e.coins = Long.parseLong(f[11]);
                e.usd = Double.parseDouble(f[12]);
                e.costUsd = Double.parseDouble(f[13]);
                e.error = nn(f[14]);
                return e;
            } catch (RuntimeException ex) {
                return null;
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Long.toString(score));
            m.put("at", Instant.ofEpochMilli(atMs).toString());
            m.put("wallet", wallet);
            m.put("app", app);
            m.put("purpose", purpose);
            m.put("provider", provider);
            m.put("model", model);
            m.put("endpoint", endpoint);
            m.put("ok", ok);
            m.put("status", status);
            m.put("error", error);
            m.put("coins", coins);
            m.put("usd", round6(usd));
            m.put("cost_usd", round6(costUsd));
            return m;
        }

        private static String nz(String s) {
            return s == null ? "" : s.replace('\t', ' ');
        }

        private static String nn(String s) {
            return s.isEmpty() ? null : s;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------------------------------

    /** Where entries and daily aggregates live. Redis in production; a map in tests. */
    interface Store {
        /**
         * Adds {@code member} at {@code score}, adds {@code increments} to {@code day}'s aggregate,
         * then trims entries scored below {@code minScore} and beyond the newest {@code maxEntries}.
         */
        CompletionStage<Void> add(long score, String member, String day, Map<String, Double> increments,
                                  long minScore, int maxEntries, long aggTtlSeconds);

        /** Up to {@code count} entries scored strictly below {@code before}, newest first. */
        CompletionStage<List<ScoredValue<String>>> before(long before, int count);

        /** Each day's aggregate hash (missing days absent). */
        CompletionStage<Map<String, Map<String, String>>> aggregates(List<String> days);
    }

    static final class RedisStore implements Store {
        static final String KEY = "aicoin:{aicoin}:tx";

        static String aggKey(String day) {
            return "aicoin:{aicoin}:tx-agg:" + day;
        }

        private final RedisAsyncCommands<String, String> redis;

        RedisStore(RedisAsyncCommands<String, String> redis) {
            this.redis = redis;
        }

        @Override
        public CompletionStage<Void> add(long score, String member, String day, Map<String, Double> increments,
                                         long minScore, int maxEntries, long aggTtlSeconds) {
            String agg = aggKey(day);
            CompletionStage<?> step = redis.zadd(KEY, (double) score, member);
            for (Map.Entry<String, Double> inc : increments.entrySet()) {
                step = step.thenCompose(v -> redis.hincrbyfloat(agg, inc.getKey(), inc.getValue()));
            }
            return step.thenCompose(v -> redis.expire(agg, aggTtlSeconds))
                    .thenCompose(v -> redis.zremrangebyscore(KEY, Range.create(Double.NEGATIVE_INFINITY, (double) minScore - 1)))
                    .thenCompose(v -> redis.zremrangebyrank(KEY, 0, -(maxEntries + 1L)))
                    .thenApply(v -> null);
        }

        @Override
        public CompletionStage<List<ScoredValue<String>>> before(long before, int count) {
            Range<Double> range = Range.from(Range.Boundary.unbounded(), Range.Boundary.excluding((double) before));
            return redis.zrevrangebyscoreWithScores(KEY, range, Limit.create(0, count));
        }

        @Override
        public CompletionStage<Map<String, Map<String, String>>> aggregates(List<String> days) {
            Map<String, Map<String, String>> out = new java.util.concurrent.ConcurrentHashMap<>();
            List<CompletableFuture<?>> all = new ArrayList<>();
            for (String day : days) {
                all.add(redis.hgetall(aggKey(day)).toCompletableFuture().thenAccept(m -> {
                    if (m != null && !m.isEmpty()) {
                        out.put(day, m);
                    }
                }));
            }
            return CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).thenApply(v -> out);
        }
    }

    /** The Redis store's semantics, in memory — for tests. */
    static final class MemoryStore implements Store {
        final NavigableMap<Long, String> entries = new TreeMap<>();
        final Map<String, Map<String, Double>> aggs = new HashMap<>();
        volatile boolean failing;

        @Override
        public synchronized CompletionStage<Void> add(long score, String member, String day,
                                                      Map<String, Double> increments, long minScore,
                                                      int maxEntries, long aggTtlSeconds) {
            if (failing) {
                return CompletableFuture.failedFuture(new RuntimeException("redis down"));
            }
            entries.put(score, member);
            Map<String, Double> agg = aggs.computeIfAbsent(day, d -> new HashMap<>());
            increments.forEach((k, v) -> agg.merge(k, v, Double::sum));
            entries.headMap(minScore, false).clear();
            while (entries.size() > maxEntries) {
                entries.pollFirstEntry();
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public synchronized CompletionStage<List<ScoredValue<String>>> before(long before, int count) {
            if (failing) {
                return CompletableFuture.failedFuture(new RuntimeException("redis down"));
            }
            List<ScoredValue<String>> out = new ArrayList<>();
            for (Map.Entry<Long, String> e : entries.headMap(before, false).descendingMap().entrySet()) {
                if (out.size() >= count) {
                    break;
                }
                out.add(ScoredValue.just((double) e.getKey(), e.getValue()));
            }
            return CompletableFuture.completedFuture(out);
        }

        @Override
        public synchronized CompletionStage<Map<String, Map<String, String>>> aggregates(List<String> days) {
            Map<String, Map<String, String>> out = new HashMap<>();
            for (String day : days) {
                Map<String, Double> agg = aggs.get(day);
                if (agg != null) {
                    Map<String, String> m = new HashMap<>();
                    agg.forEach((k, v) -> m.put(k, Double.toString(v)));
                    out.put(day, m);
                }
            }
            return CompletableFuture.completedFuture(out);
        }
    }

    // ------------------------------------------------------------------------------------------
    // The log
    // ------------------------------------------------------------------------------------------

    private static volatile Transactions instance;

    /** The installed log, or null when there is none (tests that do not install one record nothing). */
    static Transactions get() {
        return instance;
    }

    static void install(Transactions transactions) {
        instance = transactions;
    }

    /** Records {@code call} into the installed log, if any. Never throws. */
    static void recordCall(Call call) {
        Transactions t = instance;
        if (t != null) {
            t.record(call);
        }
    }

    private final Store store;
    private final double coinValueUsd;
    private final int retentionDays;
    private final int maxEntries;
    private final LongSupplier clock;
    /** The app a wallet is assigned to, for calls that name none ({@link AppCaps#resolveApp}). */
    private final Function<String, CompletionStage<Optional<String>>> walletApp;
    private final AtomicLong seq = new AtomicLong();

    Transactions(Store store, double coinValueUsd, int retentionDays, int maxEntries, LongSupplier clock,
                 Function<String, CompletionStage<Optional<String>>> walletApp) {
        this.store = store;
        this.coinValueUsd = coinValueUsd;
        this.retentionDays = Math.max(1, retentionDays);
        this.maxEntries = Math.max(1, maxEntries);
        this.clock = clock;
        this.walletApp = walletApp;
    }

    /** Production wiring: the ledger's Redis, the configured coin value, sizes from the environment. */
    static Transactions create(AicoinLedger ledger, ProxyConfig config, Map<String, String> env) {
        int days = intEnv(env, "AICOIN_PROXY_TRANSACTIONS_RETENTION_DAYS", DEFAULT_RETENTION_DAYS);
        int max = intEnv(env, "AICOIN_PROXY_TRANSACTIONS_MAX_ENTRIES", DEFAULT_MAX_ENTRIES);
        AppCaps caps = ledger.appCaps();
        return new Transactions(new RedisStore(ledger.redis()), config.getCoinValueUsd(), days, max,
                System::currentTimeMillis, wallet -> caps.resolveApp(wallet, null));
    }

    private static int intEnv(Map<String, String> env, String name, int fallback) {
        try {
            String v = env.get(name);
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            LOG.warning(name + " is not a number; using " + fallback);
            return fallback;
        }
    }

    int retentionDays() {
        return retentionDays;
    }

    /** Fire-and-forget. A failure anywhere is logged and dropped. */
    void record(Call call) {
        try {
            long now = clock.getAsLong();
            Entry e = new Entry();
            e.score = now * 1000 + Math.floorMod(seq.getAndIncrement(), 1000L);
            e.id = UUID.randomUUID().toString().substring(0, 8);
            e.atMs = now;
            e.wallet = call.wallet;
            e.purpose = call.tag.purpose() != null ? call.tag.purpose() : UNSPECIFIED;
            e.provider = label(call.provider);
            e.model = label(call.model);
            e.endpoint = call.endpoint;
            e.ok = call.ok;
            e.status = call.status;
            e.coins = call.ok ? call.coins : 0;
            e.usd = e.coins * coinValueUsd;
            e.costUsd = call.ok ? call.costUsd : 0;
            e.error = call.ok ? null : call.error;
            CompletionStage<String> app = call.tag.app() != null || call.wallet == null || walletApp == null
                    ? CompletableFuture.completedFuture(call.tag.app() != null ? call.tag.app() : UNKNOWN)
                    : walletApp.apply(call.wallet).thenApply(a -> a.orElse(UNKNOWN)).exceptionally(err -> UNKNOWN);
            app.thenCompose(a -> {
                e.app = a;
                return store.add(e.score, e.encode(), dayOf(now), increments(e),
                        (now - retentionDays * 86_400_000L) * 1000, maxEntries, (retentionDays + 2) * 86_400L);
            }).whenComplete((v, err) -> {
                if (err != null) {
                    LOG.log(Level.WARNING, "transaction record failed for " + call.wallet, err);
                }
            });
        } catch (RuntimeException err) {
            LOG.log(Level.WARNING, "transaction record failed", err);
        }
    }

    private static final char SEP = '\u001f';

    private static Map<String, Double> increments(Entry e) {
        String prefix = e.app + SEP + e.purpose + SEP + (e.ok ? "1" : "0") + SEP;
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(prefix + "n", 1.0);
        if (e.coins > 0) {
            m.put(prefix + "coins", (double) e.coins);
            m.put(prefix + "usd", e.usd);
        }
        if (e.costUsd > 0) {
            m.put(prefix + "cost", e.costUsd);
        }
        return m;
    }

    static String dayOf(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /** {@code GET /admin/transactions}'s parameters. Null filters match everything. */
    static final class Query {
        Long before;
        int limit = DEFAULT_PAGE;
        String app;
        String purpose;
        Boolean ok;
        String wallet;

        boolean matches(Entry e) {
            return (app == null || app.equalsIgnoreCase(e.app))
                    && (purpose == null || purpose.equalsIgnoreCase(e.purpose))
                    && (ok == null || ok == e.ok)
                    && (wallet == null || wallet.equalsIgnoreCase(e.wallet));
        }

        /** Parses the query string; returns an error message instead when a parameter is malformed. */
        static Object parse(Map<String, List<String>> params) {
            Query q = new Query();
            String before = first(params, "before");
            if (before != null && !before.isEmpty()) {
                try {
                    q.before = Long.parseLong(before);
                } catch (NumberFormatException e) {
                    return "before must be the next_before of a previous page";
                }
            }
            String limit = first(params, "limit");
            if (limit != null && !limit.isEmpty()) {
                try {
                    q.limit = Integer.parseInt(limit);
                } catch (NumberFormatException e) {
                    return "limit must be a number";
                }
                if (q.limit < 1 || q.limit > MAX_PAGE) {
                    return "limit must be 1-" + MAX_PAGE;
                }
            }
            q.app = label(first(params, "app"));
            q.purpose = label(first(params, "purpose"));
            String ok = first(params, "ok");
            if (ok != null && !ok.isEmpty()) {
                if (!ok.equals("true") && !ok.equals("false")) {
                    return "ok must be true or false";
                }
                q.ok = Boolean.parseBoolean(ok);
            }
            String wallet = first(params, "wallet");
            q.wallet = wallet == null || wallet.isBlank() ? null : wallet.trim();
            return q;
        }

        private static String first(Map<String, List<String>> params, String name) {
            List<String> values = params.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    /** Totals of a filtered set. */
    static final class Totals {
        long count;
        long failures;
        long coins;
        double usd;
        double costUsd;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", count);
            m.put("failures", failures);
            m.put("coins", coins);
            m.put("usd", round6(usd));
            m.put("cost_usd", round6(costUsd));
            return m;
        }
    }

    /** One page as JSON; empty when the store could not be read. */
    void query(Query q, Consumer<Optional<String>> onResult) {
        List<Entry> page = new ArrayList<>();
        long start = q.before != null ? q.before : Long.MAX_VALUE;
        CompletableFuture<Long> next = new CompletableFuture<>();
        scan(q, start, page, 0, next);
        next.thenCompose(nextBefore -> totalsAndFacets(q).thenApply(tf -> {
            Map<String, Object> out = new LinkedHashMap<>();
            List<Object> list = new ArrayList<>();
            for (Entry e : page) {
                list.add(e.toMap());
            }
            out.put("transactions", list);
            out.put("next_before", nextBefore == null ? null : Long.toString(nextBefore));
            out.put("totals", tf.totals.toMap());
            out.put("apps", new ArrayList<Object>(tf.apps));
            out.put("purposes", new ArrayList<Object>(tf.purposes));
            out.put("retention_days", retentionDays);
            out.put("coin_value_usd", coinValueUsd);
            return Json.write(out);
        })).whenComplete((json, err) -> {
            if (err != null) {
                LOG.log(Level.WARNING, "transaction query failed", err);
                onResult.accept(Optional.empty());
            } else {
                onResult.accept(Optional.of(json));
            }
        });
    }

    /** Fills {@code page} newest first from below {@code before}; completes with the next cursor, or null at the end. */
    private void scan(Query q, long before, List<Entry> page, int scanned, CompletableFuture<Long> done) {
        store.before(before, BATCH).whenComplete((batch, err) -> {
            if (err != null) {
                done.completeExceptionally(err);
                return;
            }
            int seen = scanned;
            long last = before;
            for (ScoredValue<String> sv : batch) {
                seen++;
                last = (long) sv.getScore();
                Entry e = Entry.decode(sv.getScore(), sv.getValue());
                if (e != null && q.matches(e)) {
                    page.add(e);
                    if (page.size() >= q.limit) {
                        done.complete(last);
                        return;
                    }
                }
            }
            if (batch.size() < BATCH) {
                done.complete(null);
            } else if (seen >= SCAN_CAP) {
                // Few matches this far back: hand over a cursor so the client asks again.
                done.complete(last);
            } else {
                scan(q, last, page, seen, done);
            }
        });
    }

    private static final class TotalsAndFacets {
        final Totals totals = new Totals();
        final TreeSet<String> apps = new TreeSet<>();
        final TreeSet<String> purposes = new TreeSet<>();
    }

    private CompletionStage<TotalsAndFacets> totalsAndFacets(Query q) {
        long now = clock.getAsLong();
        LocalDate today = LocalDate.parse(dayOf(now));
        List<String> days = new ArrayList<>();
        for (int i = 0; i <= retentionDays; i++) {
            days.add(today.minusDays(i).toString());
        }
        return store.aggregates(days).thenCompose(aggs -> {
            TotalsAndFacets tf = new TotalsAndFacets();
            for (Map<String, String> agg : aggs.values()) {
                for (Map.Entry<String, String> field : agg.entrySet()) {
                    String[] parts = field.getKey().split(String.valueOf(SEP), -1);
                    if (parts.length != 4) {
                        continue;
                    }
                    tf.apps.add(parts[0]);
                    tf.purposes.add(parts[1]);
                    if (q.wallet != null) {
                        continue;
                    }
                    boolean ok = "1".equals(parts[2]);
                    if ((q.app != null && !q.app.equalsIgnoreCase(parts[0]))
                            || (q.purpose != null && !q.purpose.equalsIgnoreCase(parts[1]))
                            || (q.ok != null && q.ok != ok)) {
                        continue;
                    }
                    double v;
                    try {
                        v = Double.parseDouble(field.getValue());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    switch (parts[3]) {
                        case "n" -> {
                            tf.totals.count += Math.round(v);
                            if (!ok) {
                                tf.totals.failures += Math.round(v);
                            }
                        }
                        case "coins" -> tf.totals.coins += Math.round(v);
                        case "usd" -> tf.totals.usd += v;
                        case "cost" -> tf.totals.costUsd += v;
                        default -> { }
                    }
                }
            }
            if (q.wallet == null) {
                return CompletableFuture.completedFuture(tf);
            }
            // A wallet is not in the aggregates: total its kept entries.
            CompletableFuture<TotalsAndFacets> done = new CompletableFuture<>();
            totalEntries(q, Long.MAX_VALUE, tf, done);
            return done;
        });
    }

    private void totalEntries(Query q, long before, TotalsAndFacets tf, CompletableFuture<TotalsAndFacets> done) {
        store.before(before, 2_000).whenComplete((batch, err) -> {
            if (err != null) {
                done.completeExceptionally(err);
                return;
            }
            long last = before;
            for (ScoredValue<String> sv : batch) {
                last = (long) sv.getScore();
                Entry e = Entry.decode(sv.getScore(), sv.getValue());
                if (e != null && q.matches(e)) {
                    tf.totals.count++;
                    if (!e.ok) {
                        tf.totals.failures++;
                    }
                    tf.totals.coins += e.coins;
                    tf.totals.usd += e.usd;
                    tf.totals.costUsd += e.costUsd;
                }
            }
            if (batch.size() < 2_000) {
                done.complete(tf);
            } else {
                totalEntries(q, last, tf, done);
            }
        });
    }

    static double round6(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
