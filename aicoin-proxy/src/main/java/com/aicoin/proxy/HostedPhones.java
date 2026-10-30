package com.aicoin.proxy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hosted Anyphone: an Android phone on an EC2 instance in the operator's account, paid for by the
 * minute in aicoin. Per CONTRACT.md's "Hosted Anyphone".
 *
 * <p>A phone is expensive, so it is suspended often — and suspending means terminating: the app
 * first downloads the phone's whole state over its anyphoned connection, then asks for
 * {@code POST /phone/suspend}, and nothing of the phone is left in AWS. Starting again launches a
 * fresh instance from the prebaked AMI and the app uploads the state back.
 *
 * <p>Billing: every <em>started</em> minute from just before RunInstances until the suspend, at the
 * tier's {@link PhonePricing coins per minute}, charged by {@link PhoneStore#charge} (atomic,
 * durable — a restart charges the minutes it missed, once). A balance that can no longer pay for a
 * few more minutes gets a push; one that cannot pay the next minute gets a grace period and then
 * the phone is terminated without waiting for a backup.
 *
 * <p>Nothing here may let an instance outlive its record: the meter also lists every instance
 * tagged {@code anyphone:hosted} and terminates any that no record owns, so a crash between
 * launching and recording, a record deleted by hand, or a lost terminate call cannot leave an
 * instance billing the operator with nobody paying.
 *
 * <p>Every method runs on this class's own single thread (EC2 and the store block), never on a
 * Netty event loop; within a process they never interleave. Across processes (a deploy's overlap)
 * one wallet's phone is one Redis key created with NX, the meter runs only in the process holding
 * its lease, and a charge cannot double because it advances the charged minutes in the same step.
 */
final class HostedPhones {

    private static final Logger LOG = Logger.getLogger(HostedPhones.class.getName());

    static final int PORT = 443;
    /** A launched instance may take this long to appear in DescribeInstances (eventual consistency). */
    private static final long VISIBILITY_GRACE_MILLIS = 120_000L;

    /** How a wallet is told about its phone: a push to its devices registered for the app. */
    interface Notifier {
        void notify(String wallet, String event, String phoneId, String title, String body);
    }

    /** An HTTP answer: a status and a JSON object. */
    record Reply(int status, Map<String, Object> body) {
        String json() {
            return Json.write(body);
        }
    }

    private static volatile HostedPhones installed;

    private final AnyphoneConfig config;
    private final PhoneStore store;
    private final PhoneCloud cloud;
    private final Notifier notifier;
    private final LongSupplier clock;
    private final Supplier<String> template;
    private final String owner = UUID.randomUUID().toString();
    private final SecureRandom random = new SecureRandom();
    private ScheduledExecutorService executor;
    /** Instances terminated during the current tick, so the reaper does not terminate them again off its older listing. */
    private final java.util.Set<String> terminatedThisTick = new java.util.HashSet<>();

    HostedPhones(AnyphoneConfig config, PhoneStore store, PhoneCloud cloud, Notifier notifier, LongSupplier clock,
                 Supplier<String> template) {
        this.config = config;
        this.store = store;
        this.cloud = cloud;
        this.notifier = notifier;
        this.clock = clock;
        this.template = template;
    }

    static void install(HostedPhones phones) {
        installed = phones;
    }

    /** The running service, or null when hosted phones are disabled. */
    static HostedPhones get() {
        return installed;
    }

    /** Starts the meter on its own thread. */
    synchronized void startMeter() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hosted-phones");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tickSafely, 5, config.getMeterIntervalSeconds(), TimeUnit.SECONDS);
    }

    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** Runs a request's work on the phones thread and hands the reply on from there. */
    void submit(Supplier<Reply> work, Consumer<Reply> onReply) {
        ScheduledExecutorService ex;
        synchronized (this) {
            ex = executor;
        }
        Runnable run = () -> {
            Reply reply;
            try {
                reply = work.get();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "hosted phone request failed", e);
                reply = error(503, "unavailable", "hosted phones are unavailable right now");
            }
            onReply.accept(reply);
        };
        if (ex == null) {
            run.run();
        } else {
            ex.execute(run);
        }
    }

    // ------------------------------------------------------------------ endpoints

    /** {@code GET /phone/price}. */
    synchronized Reply price() {
        List<Object> tiers = new ArrayList<>();
        for (AnyphoneConfig.Tier tier : config.getTiers().values()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", tier.getId());
            t.put("instanceType", tier.getInstanceType());
            t.put("coinsPerMinute", PhonePricing.coinsPerMinute(tier, config));
            t.put("usdPerHour", tier.getUsdPerHour());
            t.put("market", tier.isSpot() ? "spot" : "on-demand");
            t.put("available", tier.isAvailable());
            tiers.add(t);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tiers", tiers);
        body.put("minimumMinutes", config.getMinimumMinutes());
        return new Reply(200, body);
    }

    /** {@code GET /phone/status}: the wallet's own phone, token included — there is no way to ask about another's. */
    synchronized Reply status(String wallet) {
        PhoneRecord record = store.get(wallet);
        return new Reply(200, statusBody(record, store.balance(wallet)));
    }

    /** {@code POST /phone/start} for a fresh phone. */
    synchronized Reply start(String wallet, String tierId) {
        return start(wallet, tierId, false);
    }

    /**
     * {@code POST /phone/start}. {@code restore}: the app has a saved state it will upload right
     * away, so Android's first boot waits for it ({@code ANYPHONE_RESTORE=expected}).
     */
    synchronized Reply start(String wallet, String tierId, boolean restore) {
        AnyphoneConfig.Tier tier = config.tier(tierId);
        if (tier == null) {
            Reply reply = error(400, "unknown_tier", "tier must be one of " + config.getTiers().keySet());
            return reply;
        }
        if (!tier.isAvailable()) {
            return error(409, "tier_unavailable", "the " + tier.getId() + " phone cannot be started yet");
        }
        if (config.getSubnetId().isEmpty() || config.getSecurityGroupId().isEmpty()
                || config.getVersion().isEmpty() || config.getReleaseUrl().isEmpty()) {
            return error(503, "not_configured", "hosted phones are not configured");
        }
        PhoneRecord existing = store.get(wallet);
        if (existing != null) {
            Reply reply = error(409, "phone_exists", "this wallet already has a phone");
            reply.body().put("phone", statusBody(existing, store.balance(wallet)));
            return reply;
        }
        String imageId = image(tier);
        if (imageId.isEmpty()) {
            return error(503, "not_configured", "no image for the " + tier.getId() + " phone");
        }
        long cpm = PhonePricing.coinsPerMinute(tier, config);
        long required = cpm * config.getMinimumMinutes();
        double balance = store.balance(wallet);
        if (balance < required) {
            Reply reply = error(402, "insufficient_balance",
                    "starting needs " + required + " coins (" + config.getMinimumMinutes() + " minutes)");
            reply.body().put("required", required);
            reply.body().put("balance", balance);
            reply.body().put("coinsPerMinute", cpm);
            return reply;
        }

        long now = clock.getAsLong();
        PhoneRecord record = new PhoneRecord();
        record.wallet = wallet;
        record.phoneId = "ph-" + HexFormat.of().formatHex(randomBytes(8));
        record.token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        record.tier = tier.getId();
        record.instanceType = tier.getInstanceType();
        record.coinsPerMinute = cpm;
        record.state = PhoneRecord.STARTING;
        record.launchedAt = now;
        String userData = PhoneUserData.render(template.get(),
                PhoneUserData.values(config, record.phoneId, record.token, restore), config.getReleaseUrl());
        if (!store.create(record)) {
            return error(409, "phone_exists", "this wallet already has a phone");
        }

        String instanceId;
        try {
            instanceId = cloud.launch(new PhoneCloud.Launch(wallet, record.phoneId, tier, imageId, userData));
        } catch (RuntimeException e) {
            // Nothing launched, nothing charged: forget the phone so the wallet can try again.
            LOG.log(Level.WARNING, "could not launch a " + tier.getId() + " phone for " + wallet + ": " + e.getMessage());
            store.delete(wallet, record.phoneId);
            return error(502, "launch_failed", "AWS could not start the phone; try again later");
        }
        LOG.info("hosted phone " + record.phoneId + " (" + tier.getInstanceType() + ", " + instanceId + ") for " + wallet);
        update(wallet, record.phoneId, r -> r.instanceId = instanceId);
        // The first minute is charged now, not at the next meter tick.
        PhoneStore.Charge charge = store.charge(wallet, record.phoneId, PhonePricing.startedMinutes(now, now), now);
        return new Reply(200, statusBody(store.get(wallet), charge.balance()));
    }

    /**
     * {@code POST /phone/suspend}: stops the meter and terminates the instance. The app calls it
     * after downloading the phone's state; a phone that never finished starting has no state to
     * download, so suspending it needs {@code force} (and {@code force} is also how the app throws
     * a phone away without a backup).
     */
    synchronized Reply suspend(String wallet, boolean force) {
        PhoneRecord record = store.get(wallet);
        if (record == null) {
            return error(404, "no_phone", "this wallet has no phone");
        }
        if (!force && PhoneRecord.STARTING.equals(record.state)) {
            return error(409, "not_running",
                    "the phone has not finished starting; send {\"force\":true} to terminate it anyway");
        }
        Long charged = suspendNow(record, "requested", true);
        Map<String, Object> body = statusBody(charged != null ? null : store.get(wallet), store.balance(wallet));
        if (charged != null) {
            // The phone is gone; say which one ended and what it came to.
            body.put("phoneId", record.phoneId);
            body.put("tier", record.tier);
            body.put("instanceType", record.instanceType);
            body.put("coinsPerMinute", record.coinsPerMinute);
            body.put("chargedCoins", charged);
            body.put("startedAt", Instant.ofEpochMilli(record.launchedAt).toString());
        }
        return new Reply(200, body);
    }

    /**
     * Charges up to now, marks the phone suspending (the meter charges nothing more), terminates
     * the instance and forgets the phone. Returns the coins the phone was charged in all, or null
     * when AWS refused the terminate: the phone then stays {@code suspending} and the meter retries.
     */
    private Long suspendNow(PhoneRecord record, String why, boolean chargeToNow) {
        long now = clock.getAsLong();
        if (chargeToNow && !PhoneRecord.SUSPENDING.equals(record.state)) {
            store.charge(record.wallet, record.phoneId, PhonePricing.startedMinutes(record.launchedAt, now), now);
        }
        PhoneRecord current = update(record.wallet, record.phoneId, r -> r.state = PhoneRecord.SUSPENDING);
        if (current == null) {
            return record.chargedCoins;
        }
        if (!current.instanceId.isEmpty()) {
            try {
                cloud.terminate(current.instanceId);
                terminatedThisTick.add(current.instanceId);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "could not terminate " + current.instanceId + " (" + why + "): " + e.getMessage());
                return null;
            }
        }
        // An instance still unrecorded (its start is in flight in another process) is left to the
        // reaper, which terminates whatever no record owns.
        store.delete(record.wallet, record.phoneId);
        LOG.info("hosted phone " + record.phoneId + " for " + record.wallet + " suspended (" + why + "), "
                + current.billedMinutes + " min, " + current.chargedCoins + " coins");
        return current.chargedCoins;
    }

    // ------------------------------------------------------------------ meter

    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "hosted phone meter tick failed", e);
        }
    }

    /**
     * One meter pass: charge every phone its started minutes, follow each instance's state, warn
     * and suspend on a balance that has run out, and terminate any instance nobody owns.
     */
    synchronized void tick() {
        long leaseMillis = Math.max(60_000L, config.getMeterIntervalSeconds() * 3000L);
        if (!store.lease(owner, leaseMillis)) {
            return;
        }
        long now = clock.getAsLong();
        terminatedThisTick.clear();
        List<PhoneCloud.Instance> instances;
        try {
            instances = cloud.list();
        } catch (RuntimeException e) {
            // Still charge — the instances exist whether or not EC2 answered — but decide nothing
            // about their state, and terminate nothing, on a list we do not have.
            LOG.log(Level.WARNING, "could not list hosted instances: " + e.getMessage());
            instances = null;
        }
        Listing listing = instances == null ? null : new Listing(instances);
        for (String wallet : store.wallets()) {
            PhoneRecord record = store.get(wallet);
            if (record == null) {
                store.forget(wallet);
                continue;
            }
            try {
                meter(record, listing, now);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "metering " + record.phoneId + " failed", e);
            }
        }
        if (instances != null) {
            reap(instances);
        }
    }

    /** One DescribeInstances result, looked up by instance (the recorded one) or by phone (one to adopt). */
    private static final class Listing {
        final Map<String, PhoneCloud.Instance> byInstance = new HashMap<>();
        final Map<String, PhoneCloud.Instance> byPhone = new HashMap<>();

        Listing(List<PhoneCloud.Instance> instances) {
            for (PhoneCloud.Instance i : instances) {
                byInstance.put(i.instanceId(), i);
                if (!i.isGone()) {
                    byPhone.putIfAbsent(i.phoneId(), i);
                }
            }
        }
    }

    private void meter(PhoneRecord record, Listing listing, long now) {
        boolean listed = listing != null;
        if (record.instanceId.isEmpty()) {
            PhoneCloud.Instance instance = listed ? listing.byPhone.get(record.phoneId) : null;
            if (instance != null) {
                // Launched, but the process that launched it died before recording it.
                record = update(record.wallet, record.phoneId, r -> r.instanceId = instance.instanceId());
                if (record == null) {
                    return;
                }
            } else if (now - record.launchedAt > config.getStartTimeoutSeconds() * 1000L) {
                LOG.warning("hosted phone " + record.phoneId + " for " + record.wallet + " never got an instance; dropping it");
                store.delete(record.wallet, record.phoneId);
                return;
            } else {
                return;
            }
        }
        PhoneCloud.Instance instance = listed ? listing.byInstance.get(record.instanceId) : null;
        if (PhoneRecord.SUSPENDING.equals(record.state)) {
            if (listed && (instance == null || instance.isGone())) {
                store.delete(record.wallet, record.phoneId);
            } else {
                suspendNow(record, "retry", false);
            }
            return;
        }
        if (listed && (instance == null ? now - record.launchedAt > VISIBILITY_GRACE_MILLIS : instance.isGone())) {
            // Gone without a suspend: a spot interruption, or terminated by hand. Nothing more is charged.
            LOG.warning("hosted phone " + record.phoneId + " for " + record.wallet + ": instance " + record.instanceId + " is gone");
            store.delete(record.wallet, record.phoneId);
            notifier.notify(record.wallet, "phone_stopped", record.phoneId, "Your phone stopped",
                    "Its cloud machine was taken back. Start it again to restore your last backup.");
            return;
        }
        if (instance != null && instance.isStopped()) {
            // Android powered off (shutdown behaviour is stop). A hosted phone is never resumed
            // from a stopped instance: it is over. Nothing is charged for the time since it
            // stopped; terminating removes the volume. If AWS refuses, the phone stays
            // suspending (unmetered) and the next pass retries.
            LOG.info("hosted phone " + record.phoneId + " for " + record.wallet + ": instance " + record.instanceId
                    + " is " + instance.state() + "; ending it");
            if (suspendNow(record, "instance stopped", false) != null) {
                notifier.notify(record.wallet, "phone_stopped", record.phoneId, "Your phone was shut down",
                        "Android powered off, so the phone was ended. Start it again to restore your last backup.");
            }
            return;
        }
        if (instance != null && instance.isRunning() && !instance.publicIp().isEmpty()
                && (!PhoneRecord.RUNNING.equals(record.state) || !instance.publicIp().equals(record.host))) {
            record = update(record.wallet, record.phoneId, r -> {
                r.state = PhoneRecord.RUNNING;
                r.host = instance.publicIp();
            });
            if (record == null) {
                return;
            }
        }
        PhoneStore.Charge charge = store.charge(record.wallet, record.phoneId,
                PhonePricing.startedMinutes(record.launchedAt, now), now);
        if (!charge.found()) {
            return;
        }
        checkBalance(store.get(record.wallet), charge.balance(), now);
    }

    private void checkBalance(PhoneRecord record, double balance, long now) {
        if (record == null) {
            return;
        }
        long cpm = record.coinsPerMinute;
        if (balance < cpm) {
            if (record.exhaustedAt == 0) {
                update(record.wallet, record.phoneId, r -> {
                    r.exhaustedAt = now;
                    if (r.warnedAt == 0) {
                        r.warnedAt = now;
                    }
                });
                long minutes = Math.max(1, config.getGraceSeconds() / 60);
                notifier.notify(record.wallet, "balance_exhausted", record.phoneId, "Your phone is about to be suspended",
                        "Out of AICoins. Add coins within " + minutes + " min or the phone is suspended without a backup.");
            } else if (now - record.exhaustedAt >= config.getGraceSeconds() * 1000L) {
                if (suspendNow(record, "balance exhausted", true) != null) {
                    notifier.notify(record.wallet, "suspended", record.phoneId, "Your phone was suspended",
                            "It ran out of AICoins. Changes since your last backup were not saved.");
                }
            }
            return;
        }
        if (balance < cpm * config.getWarnMinutes()) {
            boolean warn = record.warnedAt == 0;
            if (warn || record.exhaustedAt != 0) {
                update(record.wallet, record.phoneId, r -> {
                    r.exhaustedAt = 0;
                    if (r.warnedAt == 0) {
                        r.warnedAt = now;
                    }
                });
            }
            if (warn) {
                long minutes = (long) Math.floor(balance / cpm);
                notifier.notify(record.wallet, "balance_low", record.phoneId, "AICoins running low",
                        "Your phone has about " + minutes + " min left. Add coins to keep it running.");
            }
            return;
        }
        if (record.warnedAt != 0 || record.exhaustedAt != 0) {
            update(record.wallet, record.phoneId, r -> {
                r.warnedAt = 0;
                r.exhaustedAt = 0;
            });
        }
    }

    /** Terminates every hosted instance that no record owns. */
    private void reap(List<PhoneCloud.Instance> instances) {
        for (PhoneCloud.Instance instance : instances) {
            if (instance.isGone() || terminatedThisTick.contains(instance.instanceId())) {
                continue;
            }
            PhoneRecord record = instance.wallet().isEmpty() ? null : store.get(instance.wallet());
            boolean owned = record != null && record.phoneId.equals(instance.phoneId())
                    && (record.instanceId.isEmpty() || record.instanceId.equals(instance.instanceId()));
            if (owned) {
                continue;
            }
            LOG.warning("reaping hosted instance " + instance.instanceId() + " (phone " + instance.phoneId()
                    + ", wallet " + instance.wallet() + "): no record owns it");
            try {
                cloud.terminate(instance.instanceId());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "could not reap " + instance.instanceId() + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static final long IMAGE_CACHE_MILLIS = 10 * 60_000L;
    private final Map<String, String> imageByTier = new HashMap<>();
    private final Map<String, Long> imageCheckedAt = new HashMap<>();

    /**
     * The AMI a tier launches: the newest baked image for the configured version and the tier's
     * GPU flavour (looked up at most every ten minutes), else the configured id; "" when neither.
     */
    private String image(AnyphoneConfig.Tier tier) {
        String fallback = config.amiFor(tier);
        if (!config.isBakedAmi()) {
            return fallback;
        }
        long now = clock.getAsLong();
        Long checked = imageCheckedAt.get(tier.getId());
        if (checked == null || now - checked >= IMAGE_CACHE_MILLIS) {
            try {
                String found = cloud.newestBakedImage(config.getVersion(), tier.getImageGpu());
                imageByTier.put(tier.getId(), found == null ? "" : found);
                imageCheckedAt.put(tier.getId(), now);
                if (found == null) {
                    LOG.warning("no baked anyphone image for " + config.getVersion() + "/" + tier.getImageGpu()
                            + "; using " + (fallback.isEmpty() ? "nothing" : fallback));
                }
            } catch (RuntimeException e) {
                // Not cached: the next start asks again.
                LOG.log(Level.WARNING, "could not look up baked anyphone images: " + e.getMessage());
                return fallback;
            }
        }
        String baked = imageByTier.getOrDefault(tier.getId(), "");
        return baked.isEmpty() ? fallback : baked;
    }

    /**
     * Applies {@code change} to the stored phone and saves it, re-reading and re-applying when a
     * charge (or another process) wrote it in between. Returns the saved record, or null when the
     * phone is gone or is no longer this one.
     */
    private PhoneRecord update(String wallet, String phoneId, Consumer<PhoneRecord> change) {
        for (int attempt = 0; attempt < 5; attempt++) {
            PhoneRecord current = store.get(wallet);
            if (current == null || !current.phoneId.equals(phoneId)) {
                return null;
            }
            change.accept(current);
            if (store.save(current)) {
                return current;
            }
        }
        throw new IllegalStateException("could not save phone " + phoneId + " after 5 attempts");
    }

    private Map<String, Object> statusBody(PhoneRecord record, double balance) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean has = record != null;
        body.put("phoneId", has ? record.phoneId : null);
        body.put("tier", has ? record.tier : null);
        body.put("instanceType", has ? record.instanceType : null);
        body.put("state", has ? record.state : "none");
        body.put("host", has && !record.host.isEmpty() ? record.host : null);
        body.put("port", PORT);
        body.put("token", has ? record.token : null);
        body.put("coinsPerMinute", has ? record.coinsPerMinute : null);
        body.put("chargedCoins", has ? record.chargedCoins : 0L);
        body.put("startedAt", has ? Instant.ofEpochMilli(record.launchedAt).toString() : null);
        body.put("balance", balance);
        body.put("suspendAt", has && record.exhaustedAt != 0
                ? Instant.ofEpochMilli(record.exhaustedAt + config.getGraceSeconds() * 1000L).toString() : null);
        return body;
    }

    private static Reply error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return new Reply(status, body);
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    /** The push for a phone event: an alert, plus {@code anyphone} so the app knows which phone and why. */
    static String payload(String title, String body, String event, String phoneId) {
        return "{\"aps\":{\"alert\":{\"title\":" + Json.string(title) + ",\"body\":" + Json.string(body)
                + "},\"sound\":\"default\"},\"anyphone\":{\"event\":" + Json.string(event)
                + ",\"phoneId\":" + Json.string(phoneId) + "}}";
    }

    /** The production notifier: {@link PushDevices}, to the wallet's devices registered for the Anyphone app. */
    static Notifier pushNotifier(String topic) {
        return (wallet, event, phoneId, title, body) -> {
            PushDevices devices = PushDevices.get();
            if (devices == null) {
                return;
            }
            devices.push(wallet, topic, payload(title, body, event, phoneId),
                    results -> LOG.info("phone " + phoneId + " " + event + " push to " + wallet + ": " + results));
        };
    }
}
