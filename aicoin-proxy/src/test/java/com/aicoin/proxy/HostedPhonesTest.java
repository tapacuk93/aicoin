package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HostedPhones} against an in-memory store and a fake EC2 — no AWS, no Redis. The clock is
 * the test's, so minutes pass on demand.
 */
class HostedPhonesTest {

    private static final String A = "walletA";
    private static final String B = "walletB";
    private static final long MIN = 60_000L;
    /** The cpu tier at the default config: $0.38/h -> 2 coins a minute. */
    private static final long CPM = 2;

    /** EC2, faked: instances it launched, their states, and what was terminated. */
    static final class FakeCloud implements PhoneCloud {
        final List<Launch> launches = new ArrayList<>();
        final List<Instance> instances = new ArrayList<>();
        final List<String> terminated = new ArrayList<>();
        boolean failLaunch;
        boolean failTerminate;
        boolean failList;
        int next = 1;
        /** Baked images by "version/gpu"; lookups counted. */
        final Map<String, String> baked = new java.util.HashMap<>();
        int imageLookups;

        @Override
        public synchronized String newestBakedImage(String version, String gpu) {
            imageLookups++;
            return baked.get(version + "/" + gpu);
        }

        @Override
        public synchronized String launch(Launch launch) {
            if (failLaunch) {
                throw new IllegalStateException("VcpuLimitExceeded");
            }
            launches.add(launch);
            String id = "i-" + (next++);
            instances.add(new Instance(id, "pending", "", launch.wallet(), launch.phoneId()));
            return id;
        }

        @Override
        public synchronized List<Instance> list() {
            if (failList) {
                throw new IllegalStateException("throttled");
            }
            List<Instance> out = new ArrayList<>();
            for (Instance i : instances) {
                if (!"terminated".equals(i.state())) {
                    out.add(i);
                }
            }
            return out;
        }

        @Override
        public synchronized void terminate(String instanceId) {
            if (failTerminate) {
                throw new IllegalStateException("UnauthorizedOperation");
            }
            terminated.add(instanceId);
            set(instanceId, "terminated", "");
        }

        synchronized void set(String instanceId, String state, String ip) {
            for (int k = 0; k < instances.size(); k++) {
                Instance i = instances.get(k);
                if (i.instanceId().equals(instanceId)) {
                    instances.set(k, new Instance(i.instanceId(), state, ip, i.wallet(), i.phoneId()));
                }
            }
        }

        synchronized void add(Instance instance) {
            instances.add(instance);
        }
    }

    record Push(String wallet, String event, String phoneId) {
    }

    private PhoneStore.Memory store;
    private FakeCloud cloud;
    private List<Push> pushes;
    private AtomicLong clock;

    @BeforeEach
    void setUp() {
        store = new PhoneStore.Memory();
        cloud = new FakeCloud();
        pushes = new ArrayList<>();
        clock = new AtomicLong(1_700_000_000_000L);
        store.clock = clock::get;
    }

    private static AnyphoneConfig config(boolean gpuAvailable) {
        Map<String, AnyphoneConfig.Tier> tiers = AnyphoneConfig.defaultTiers();
        tiers.put("gpu", new AnyphoneConfig.Tier("gpu", "g4dn.metal", 7.82, false, gpuAvailable, false, "", "nvidia"));
        return new AnyphoneConfig(true, "us-east-1", "ami-123", true, "subnet-1", "sg-1", 40, "v0.2.0",
                "https://releases.example.com/releases", "", "com.oeaio.anyphone", 0.009, 0.5, 10, 5, 180, 15, 300, tiers);
    }

    private HostedPhones phones() {
        return phones(config(false));
    }

    private HostedPhones phones(AnyphoneConfig config) {
        return new HostedPhones(config, store, cloud,
                (wallet, event, phoneId, title, body) -> pushes.add(new Push(wallet, event, phoneId)),
                clock::get, () -> PhoneUserData.template(config));
    }

    private void advance(long millis) {
        clock.addAndGet(millis);
    }

    private HostedPhones.Reply startCpu(HostedPhones phones, String wallet) {
        return phones.start(wallet, "cpu");
    }

    // ------------------------------------------------------------------ price

    @Test
    void priceListsBothTiersAndTheGpuIsUnavailableUntilConfigured() {
        HostedPhones.Reply reply = phones().price();
        assertEquals(200, reply.status());
        String json = reply.json();
        assertEquals("{\"tiers\":["
                + "{\"id\":\"gpu\",\"instanceType\":\"g4dn.metal\",\"coinsPerMinute\":32,\"usdPerHour\":7.82,\"market\":\"on-demand\",\"available\":false},"
                + "{\"id\":\"cpu\",\"instanceType\":\"c8i.2xlarge\",\"coinsPerMinute\":2,\"usdPerHour\":0.38,\"market\":\"on-demand\",\"available\":true}"
                + "],\"minimumMinutes\":10}", json);
    }

    // ------------------------------------------------------------------ start

    @Test
    void startNeedsTheMinimumMinutesAndLaunchesNothingWithout() {
        store.balances.put(A, CPM * 10 - 1.0);
        HostedPhones.Reply reply = startCpu(phones(), A);
        assertEquals(402, reply.status());
        assertEquals("insufficient_balance", reply.body().get("error"));
        assertEquals(20L, reply.body().get("required"));
        assertTrue(cloud.launches.isEmpty());
        assertNull(store.get(A));
        assertEquals(CPM * 10 - 1.0, store.balance(A), 1e-9);
    }

    @Test
    void startLaunchesTaggedInstanceWithHostedUserDataAndChargesTheFirstMinute() {
        store.balances.put(A, 100.0);
        HostedPhones.Reply reply = startCpu(phones(), A);
        assertEquals(200, reply.status(), reply.json());
        assertEquals(1, cloud.launches.size());
        PhoneCloud.Launch launch = cloud.launches.get(0);
        assertEquals(A, launch.wallet());
        assertEquals("c8i.2xlarge", launch.tier().getInstanceType());

        Map<String, Object> body = reply.body();
        String token = (String) body.get("token");
        assertNotNull(token);
        assertEquals(43, token.length(), "32 random bytes, base64url, no padding");
        assertEquals("starting", body.get("state"));
        assertEquals(launch.phoneId(), body.get("phoneId"));
        assertEquals(443, body.get("port"));
        assertEquals(CPM, body.get("coinsPerMinute"));
        assertEquals(CPM, body.get("chargedCoins"));
        assertEquals(100.0 - CPM, (Double) body.get("balance"), 1e-9);
        assertNull(body.get("host"));

        assertTrue(launch.userData().contains("ANYPHONE_HOSTED=1"));
        assertTrue(launch.userData().contains("ANYPHONE_TOKEN=" + token + "\n"));
        assertTrue(launch.userData().contains("ANYPHONE_PHONE_ID=" + launch.phoneId() + "\n"));
        assertEquals("i-1", store.get(A).instanceId);
    }

    @Test
    void onePhonePerWallet() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        assertEquals(200, startCpu(phones, A).status());
        HostedPhones.Reply second = startCpu(phones, A);
        assertEquals(409, second.status());
        assertEquals("phone_exists", second.body().get("error"));
        assertEquals(1, cloud.launches.size());
    }

    @Test
    void anUnavailableTierIsRefusedBeforeAnythingIsLaunched() {
        store.balances.put(A, 10_000.0);
        HostedPhones.Reply reply = phones().start(A, "gpu");
        assertEquals(409, reply.status());
        assertEquals("tier_unavailable", reply.body().get("error"));
        assertTrue(cloud.launches.isEmpty());
        assertEquals(400, phones().start(A, "tpu").status());
    }

    @Test
    void anAvailableGpuTierLaunchesAtItsOwnRate() {
        store.balances.put(A, 10_000.0);
        HostedPhones.Reply reply = phones(config(true)).start(A, "gpu");
        assertEquals(200, reply.status());
        assertEquals("g4dn.metal", cloud.launches.get(0).tier().getInstanceType());
        assertEquals(32L, reply.body().get("chargedCoins"));
    }

    @Test
    void aFailedLaunchChargesNothingAndLeavesNoPhone() {
        store.balances.put(A, 100.0);
        cloud.failLaunch = true;
        HostedPhones.Reply reply = startCpu(phones(), A);
        assertEquals(502, reply.status());
        assertFalse(reply.json().contains("VcpuLimitExceeded"), "AWS's message is logged, not returned");
        assertNull(store.get(A));
        assertEquals(100.0, store.balance(A), 1e-9);
    }

    // ------------------------------------------------------------------ metering

    @Test
    void chargesEveryStartedMinuteOnceAndFollowsTheInstanceToRunning() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        cloud.set("i-1", "running", "203.0.113.7");

        advance(5 * MIN + 30_000);
        phones.tick();
        phones.tick();
        phones.tick();
        PhoneRecord record = store.get(A);
        assertEquals(6, record.billedMinutes, "minutes 1..6 have started");
        assertEquals(6 * CPM, record.chargedCoins);
        assertEquals(1000.0 - 6 * CPM, store.balance(A), 1e-9);
        assertEquals("running", record.state);

        Map<String, Object> status = phones.status(A).body();
        assertEquals("running", status.get("state"));
        assertEquals("203.0.113.7", status.get("host"));
        assertEquals(6 * CPM, status.get("chargedCoins"));
        assertEquals(1000.0 - 6 * CPM, (Double) status.get("balance"), 1e-9);
    }

    @Test
    void aRestartChargesTheMinutesItMissedExactlyOnce() {
        store.balances.put(A, 1000.0);
        HostedPhones before = phones();
        startCpu(before, A);
        cloud.set("i-1", "running", "203.0.113.7");
        advance(2 * MIN);
        before.tick();
        assertEquals(3, store.get(A).billedMinutes);
        String token = store.get(A).token;

        // The process dies; nothing ticks for ten minutes; a new process (new owner, same Redis)
        // comes up once the old one's meter lease has lapsed.
        advance(10 * MIN);
        HostedPhones after = phones();
        after.tick();
        after.tick();
        assertEquals(13, store.get(A).billedMinutes, "minutes 4..13 charged on the first tick after the restart");
        assertEquals(13 * CPM, store.get(A).chargedCoins);
        assertEquals(1000.0 - 13 * CPM, store.balance(A), 1e-9);
        assertEquals(token, after.status(A).body().get("token"));
        assertTrue(cloud.terminated.isEmpty(), "the restarted process keeps the phone it did not launch");
    }

    @Test
    void anotherProcessHoldingTheMeterLeaseDoesNotChargeTwice() {
        store.balances.put(A, 1000.0);
        HostedPhones first = phones();
        startCpu(first, A);
        cloud.set("i-1", "running", "203.0.113.7");
        advance(MIN);
        first.tick();
        String leaseOwner = store.leaseOwner;
        long charged = store.get(A).chargedCoins;
        HostedPhones second = phones();
        advance(30_000);
        second.tick(); // the lease is first's: a no-op, however many minutes are due
        assertEquals(leaseOwner, store.leaseOwner);
        assertEquals(charged, store.get(A).chargedCoins);
        advance(30_000);
        first.tick();
        assertEquals(3, store.get(A).billedMinutes);
    }

    @Test
    void anInstanceLaunchedButNeverRecordedIsAdopted() {
        store.balances.put(A, 1000.0);
        PhoneRecord record = new PhoneRecord();
        record.wallet = A;
        record.phoneId = "ph-crashed";
        record.tier = "cpu";
        record.instanceType = "c8i.2xlarge";
        record.coinsPerMinute = CPM;
        record.token = "t";
        record.launchedAt = clock.get();
        store.create(record);
        cloud.add(new PhoneCloud.Instance("i-77", "running", "198.51.100.2", A, "ph-crashed"));
        advance(MIN);
        phones().tick();
        PhoneRecord adopted = store.get(A);
        assertEquals("i-77", adopted.instanceId);
        assertEquals("running", adopted.state);
        assertEquals(2, adopted.billedMinutes);
        assertTrue(cloud.terminated.isEmpty());
    }

    @Test
    void aRecordThatNeverGotAnInstanceIsDroppedAfterTheStartTimeout() {
        PhoneRecord record = new PhoneRecord();
        record.wallet = A;
        record.phoneId = "ph-lost";
        record.coinsPerMinute = CPM;
        record.token = "t";
        record.launchedAt = clock.get();
        store.create(record);
        advance(60_000);
        phones().tick();
        assertNotNull(store.get(A), "still within the start timeout");
        advance(300_000);
        phones().tick();
        assertNull(store.get(A));
    }

    @Test
    void anInstanceThatDisappearsEndsThePhoneAndTellsTheWallet() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        cloud.set("i-1", "running", "203.0.113.7");
        advance(3 * MIN);
        phones.tick();
        long charged = store.get(A).chargedCoins;
        cloud.set("i-1", "shutting-down", ""); // a spot interruption
        advance(MIN);
        phones.tick();
        assertNull(store.get(A));
        assertEquals(1000.0 - charged, store.balance(A), 1e-9, "nothing charged after it went");
        assertEquals(List.of(new Push(A, "phone_stopped", cloud.launches.get(0).phoneId())), pushes);
    }

    // ------------------------------------------------------------------ balance

    @Test
    void lowBalanceWarnsOnceThenExhaustionSuspendsAfterTheGracePeriod() {
        // 10 minutes' minimum, plus 1 coin.
        store.balances.put(A, CPM * 10 + 1.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        String phoneId = cloud.launches.get(0).phoneId();
        cloud.set("i-1", "running", "203.0.113.7");

        // After minute 7 starts: 21 - 14 = 7 coins = 3.5 minutes left (< 5): warn.
        advance(6 * MIN);
        phones.tick();
        phones.tick();
        assertEquals(List.of(new Push(A, "balance_low", phoneId)), pushes);

        // Minute 10 starts: 21 - 20 = 1 coin, cannot pay the next minute: grace begins.
        advance(3 * MIN);
        phones.tick();
        assertEquals(new Push(A, "balance_exhausted", phoneId), pushes.get(1));
        Object suspendAt = phones.status(A).body().get("suspendAt");
        assertNotNull(suspendAt);
        assertTrue(cloud.terminated.isEmpty());

        // Still within the 180 s grace: running, charged into debt.
        advance(2 * MIN);
        phones.tick();
        assertNotNull(store.get(A));
        assertTrue(cloud.terminated.isEmpty());
        assertTrue(store.balance(A) < 0);

        // Grace over: terminated without waiting for a backup.
        advance(MIN);
        phones.tick();
        assertEquals(List.of("i-1"), cloud.terminated);
        assertNull(store.get(A));
        assertEquals(new Push(A, "suspended", phoneId), pushes.get(pushes.size() - 1));
        // Every started minute, 13 of them, and no more.
        assertEquals(CPM * 10 + 1.0 - 13 * CPM, store.balance(A), 1e-9);
        assertEquals("none", phones.status(A).body().get("state"));
    }

    @Test
    void aTopUpDuringTheGracePeriodKeepsThePhone() {
        store.balances.put(A, CPM * 10 + 1.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        cloud.set("i-1", "running", "203.0.113.7");
        advance(9 * MIN);
        phones.tick();
        assertNotNull(phones.status(A).body().get("suspendAt"));

        store.balances.merge(A, 500.0, Double::sum); // an IAP redeemed
        advance(MIN);
        phones.tick();
        assertNull(phones.status(A).body().get("suspendAt"));
        advance(5 * MIN);
        phones.tick();
        assertNotNull(store.get(A));
        assertTrue(cloud.terminated.isEmpty());
        assertEquals(0, store.get(A).warnedAt, "a later shortfall warns again");
    }

    // ------------------------------------------------------------------ suspend

    @Test
    void suspendTerminatesAndStopsTheMeter() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        String phoneId = cloud.launches.get(0).phoneId();
        cloud.set("i-1", "running", "203.0.113.7");
        advance(4 * MIN + 1);
        phones.tick();

        HostedPhones.Reply reply = phones.suspend(A, false);
        assertEquals(200, reply.status(), reply.json());
        assertEquals("none", reply.body().get("state"));
        assertEquals(phoneId, reply.body().get("phoneId"));
        assertEquals(5 * CPM, reply.body().get("chargedCoins"));
        assertNull(reply.body().get("token"));
        assertEquals(List.of("i-1"), cloud.terminated);

        double after = store.balance(A);
        advance(30 * MIN);
        phones.tick();
        assertEquals(after, store.balance(A), 1e-9);
        assertEquals(404, phones.suspend(A, false).status());
    }

    @Test
    void aPhoneStillStartingNeedsForceToSuspend() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        HostedPhones.Reply refused = phones.suspend(A, false);
        assertEquals(409, refused.status());
        assertEquals("not_running", refused.body().get("error"));
        assertTrue(cloud.terminated.isEmpty());
        assertEquals(200, phones.suspend(A, true).status());
        assertEquals(List.of("i-1"), cloud.terminated);
        assertNull(store.get(A));
    }

    @Test
    void aRefusedTerminateLeavesThePhoneSuspendingUnchargedAndIsRetried() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        cloud.set("i-1", "running", "203.0.113.7");
        phones.tick();
        cloud.failTerminate = true;
        HostedPhones.Reply reply = phones.suspend(A, false);
        assertEquals("suspending", reply.body().get("state"));
        double balance = store.balance(A);

        advance(5 * MIN);
        phones.tick();
        assertEquals("suspending", store.get(A).state);
        assertEquals(balance, store.balance(A), 1e-9, "suspending is not metered");

        cloud.failTerminate = false;
        phones.tick();
        assertEquals(List.of("i-1"), cloud.terminated);
        phones.tick();
        assertNull(store.get(A));
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void aWalletSeesAndControlsOnlyItsOwnPhone() {
        store.balances.put(A, 1000.0);
        store.balances.put(B, 7.0);
        HostedPhones phones = phones();
        String tokenA = (String) startCpu(phones, A).body().get("token");

        Map<String, Object> statusB = phones.status(B).body();
        assertEquals("none", statusB.get("state"));
        assertNull(statusB.get("token"));
        assertNull(statusB.get("phoneId"));
        assertEquals(7.0, (Double) statusB.get("balance"), 1e-9);
        assertFalse(phones.status(B).json().contains(tokenA));
        assertFalse(phones.price().json().contains(tokenA));

        assertEquals(404, phones.suspend(B, true).status());
        assertNotNull(store.get(A));
        assertTrue(cloud.terminated.isEmpty());
        assertEquals(tokenA, phones.status(A).body().get("token"));
    }

    // ------------------------------------------------------------------ reaper

    @Test
    void theReaperTerminatesEveryInstanceNoRecordOwns() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        String phoneId = cloud.launches.get(0).phoneId();
        cloud.add(new PhoneCloud.Instance("i-orphan", "running", "198.51.100.9", B, "ph-gone"));
        cloud.add(new PhoneCloud.Instance("i-stale", "running", "198.51.100.10", A, "ph-older"));
        cloud.add(new PhoneCloud.Instance("i-dup", "running", "198.51.100.11", A, phoneId));
        cloud.add(new PhoneCloud.Instance("i-untagged", "stopped", "", "", ""));
        phones.tick();
        assertEquals(List.of("i-orphan", "i-stale", "i-dup", "i-untagged"), cloud.terminated);
        assertNotNull(store.get(A));
    }

    @Test
    void noListingMeansNoReapingButTheMeterStillCharges() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        cloud.add(new PhoneCloud.Instance("i-orphan", "running", "198.51.100.9", B, "ph-gone"));
        cloud.failList = true;
        advance(3 * MIN);
        phones.tick();
        assertTrue(cloud.terminated.isEmpty());
        assertEquals(4, store.get(A).billedMinutes);
    }

    @Test
    void pushPayloadNamesThePhoneAndTheEvent() {
        assertEquals("{\"aps\":{\"alert\":{\"title\":\"T\",\"body\":\"B\"},\"sound\":\"default\"},"
                + "\"anyphone\":{\"event\":\"balance_low\",\"phoneId\":\"ph-1\"}}",
                HostedPhones.payload("T", "B", "balance_low", "ph-1"));
    }

    // ------------------------------------------------------------------ hosted contract

    @Test
    void restoreAsksTheFirstBootToWaitForTheUpload() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        assertEquals(200, phones.start(A, "cpu", true).status());
        assertTrue(cloud.launches.get(0).userData().contains("\nANYPHONE_RESTORE=expected\n"));
        phones.suspend(A, true);
        assertEquals(200, phones.start(A, "cpu", false).status());
        assertTrue(cloud.launches.get(1).userData().contains("\nANYPHONE_RESTORE=\n"));
        assertTrue(cloud.launches.get(1).userData().contains("\nANYPHONE_REPO=https://releases.example.com/releases\n"));
    }

    @Test
    void launchesTheNewestBakedImageOfTheTiersFlavourAndCachesTheLookup() {
        store.balances.put(A, 10_000.0);
        store.balances.put(B, 10_000.0);
        cloud.baked.put("v0.2.0/none", "ami-baked-cpu");
        cloud.baked.put("v0.2.0/nvidia", "ami-baked-gpu");
        HostedPhones phones = phones(config(true));
        phones.start(A, "cpu");
        phones.start(B, "gpu");
        assertEquals("ami-baked-cpu", cloud.launches.get(0).imageId());
        assertEquals("ami-baked-gpu", cloud.launches.get(1).imageId());
        phones.suspend(A, true);
        phones.start(A, "cpu");
        assertEquals(2, cloud.imageLookups, "one lookup per tier within ten minutes");
    }

    @Test
    void withNoBakedImageTheConfiguredAmiIsUsed() {
        store.balances.put(A, 1000.0);
        startCpu(phones(), A);
        assertEquals("ami-123", cloud.launches.get(0).imageId());
    }

    @Test
    void withoutAReleaseUrlNothingStarts() {
        store.balances.put(A, 1000.0);
        AnyphoneConfig c = new AnyphoneConfig(true, "us-east-1", "ami-123", true, "subnet-1", "sg-1", 40, "v0.2.0",
                "", "", "com.oeaio.anyphone", 0.009, 0.5, 10, 5, 180, 15, 300, AnyphoneConfig.defaultTiers());
        HostedPhones.Reply reply = phones(c).start(A, "cpu");
        assertEquals(503, reply.status());
        assertEquals("not_configured", reply.body().get("error"));
        assertTrue(cloud.launches.isEmpty());
    }

    @Test
    void aStoppedInstanceEndsThePhoneIsTerminatedAndChargesNothingMore() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        String phoneId = cloud.launches.get(0).phoneId();
        cloud.set("i-1", "running", "203.0.113.7");
        advance(3 * MIN);
        phones.tick();
        double balance = store.balance(A);
        cloud.set("i-1", "stopped", ""); // Android powered off; shutdown behaviour is stop
        advance(5 * MIN);
        phones.tick();
        assertEquals(List.of("i-1"), cloud.terminated);
        assertNull(store.get(A));
        assertEquals(balance, store.balance(A), 1e-9, "no minutes charged for the stopped time");
        assertEquals(List.of(new Push(A, "phone_stopped", phoneId)), pushes);
    }

    @Test
    void aStoppingInstanceWhoseTerminateFailsStaysSuspendingUnmetered() {
        store.balances.put(A, 1000.0);
        HostedPhones phones = phones();
        startCpu(phones, A);
        cloud.set("i-1", "stopping", "");
        cloud.failTerminate = true;
        double balance = store.balance(A);
        advance(4 * MIN);
        phones.tick();
        assertEquals("suspending", store.get(A).state);
        advance(4 * MIN);
        phones.tick();
        assertEquals(balance, store.balance(A), 1e-9);
        cloud.failTerminate = false;
        phones.tick();
        assertEquals(List.of("i-1"), cloud.terminated);
    }
}
