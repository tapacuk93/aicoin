package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * "Chapter ready" by push: sent when a durable call ends with nobody watching, never when a reader
 * has the stream open, with the failure wording when it fails, never for a call without an id —
 * and a device APNs calls dead is forgotten.
 */
class ConsortiumPushTest {

    private static final String WALLET = "b".repeat(64);
    private static final String TOPIC = "com.oeaio.infiniteaibook";

    /** Records every push instead of sending it; answers each with {@code answer}. */
    private static final class FakeSender implements PushDevices.Sender {
        final List<String> sent = new ArrayList<>();
        final List<PushDevices.Device> to = new ArrayList<>();
        Apns.SendResult answer = new Apns.SendResult(true, false, "200");

        @Override
        public synchronized CompletableFuture<Apns.SendResult> send(PushDevices.Device device, String payload) {
            sent.add(payload);
            to.add(device);
            return CompletableFuture.completedFuture(answer);
        }
    }

    private final FakeSender sender = new FakeSender();
    private final PushDevices.Memory store = new PushDevices.Memory();

    @AfterEach
    void uninstall() {
        PushDevices.install(null);
    }

    private PushDevices installed() {
        PushDevices devices = new PushDevices(store, sender, null);
        PushDevices.install(devices);
        devices.register(WALLET, new PushDevices.Device(TOPIC, "ab".repeat(32), "sandbox")).join();
        devices.register(WALLET, new PushDevices.Device("com.other.app", "cd".repeat(32), "production")).join();
        return devices;
    }

    private static Map<String, Object> chapterNotify() {
        Map<String, Object> notify = new LinkedHashMap<>();
        notify.put("topic", TOPIC);
        notify.put("title", "Chapter ready");
        notify.put("body", "Trade routes");
        notify.put("data", Map.of("book", "salt-road", "ref", "3.2"));
        notify.put("failure_title", "Chapter failed");
        notify.put("failure_body", "Tap to try again");
        return notify;
    }

    /**
     * A durable call at the point of ending: restored from a checkpoint whose stage is already
     * over, so resuming it goes straight to its outcome — success, or (with {@code fail}) no
     * panelist having answered — without any provider being called.
     */
    private static ConsortiumSession endingCall(String callId, boolean fail) {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());
        ConsortiumSession session = new ConsortiumSession(config, null, null, null, WALLET, callId, null,
                "Write 3.2", null, List.of("anthropic"), "anthropic", Map.of("anthropic", List.of("claude-opus-5-5")),
                1, false, false, false, "writing", null, true, 4000, null, UpstreamTimer.NONE, 0, 0, Map.of());
        session.notifyOnFinish(chapterNotify());
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) Json.load(Json.write(session.snapshot()));
        state.put("phase", fail ? "draft" : "over");
        state.put("drafts_finished", List.of("anthropic"));
        state.put("answer", "<p>The chapter</p>");
        return ConsortiumSession.restore(state, List.of(), "{\"prompt\":\"Write 3.2\"}", config, null, null, null, null);
    }

    @Test
    void aCallThatEndsWithNobodyWatchingPushesToTheWalletsDevicesForThatApp() {
        installed();
        ConsortiumSession call = endingCall("chapter-3-2", false);
        call.resume();

        assertEquals(1, sender.sent.size(), "only the device registered for this app's topic");
        assertEquals(TOPIC, sender.to.get(0).topic());
        assertTrue(sender.to.get(0).sandbox(), "a development build is pushed through the sandbox host");
        Map<?, ?> payload = (Map<?, ?>) Json.load(sender.sent.get(0));
        Map<?, ?> alert = (Map<?, ?>) ((Map<?, ?>) payload.get("aps")).get("alert");
        assertEquals("Chapter ready", alert.get("title"));
        assertEquals("Trade routes", alert.get("body"));
        assertEquals("default", ((Map<?, ?>) payload.get("aps")).get("sound"));
        assertEquals("3.2", payload.get("ref"), "the caller's data rides beside aps");
        assertEquals("chapter-3-2", payload.get("call_id"));
        assertEquals(true, call.snapshot().get("notified"));
        assertTrue(((String) call.snapshot().get("final_body")).contains("\"notified\":true"));
    }

    @Test
    void aReaderWithTheStreamOpenIsNotPushedTo() {
        installed();
        ConsortiumSession call = endingCall("chapter-3-2", false);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        call.attach(channel.pipeline().firstContext(), true);
        call.resume();
        channel.runPendingTasks();

        assertTrue(sender.sent.isEmpty());
        assertEquals(false, call.snapshot().get("notified"));
        // And coming back for it later sends nothing either: the push decision was made at the end.
        EmbeddedChannel later = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        call.attach(later.pipeline().firstContext(), true);
        assertTrue(sender.sent.isEmpty());
    }

    @Test
    void aFailedCallPushesTheFailureWording() {
        installed();
        endingCall("chapter-3-2", true).resume();
        Map<?, ?> alert = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) Json.load(sender.sent.get(0))).get("aps")).get("alert");
        assertEquals("Chapter failed", alert.get("title"));
        assertEquals("Tap to try again", alert.get("body"));
    }

    @Test
    void aCallWithoutAnIdNeverPushes() {
        installed();
        endingCall(null, false).resume();
        assertTrue(sender.sent.isEmpty(), "only a durable call runs on without a client to be told");
    }

    @Test
    void aDeviceApnsCallsDeadIsForgotten() {
        PushDevices devices = installed();
        sender.answer = new Apns.SendResult(false, true, "410 {\"reason\":\"Unregistered\"}");
        List<String> results = new ArrayList<>();
        devices.push(WALLET, TOPIC, "{}", results::addAll);
        assertEquals(1, results.size());
        assertTrue(results.get(0).contains("dropped"), results.toString());
        assertEquals(List.of("com.other.app"),
                devices.listForTest(WALLET).stream().map(PushDevices.Device::topic).toList());
    }

    @Test
    void aWalletKeepsItsNewestTenDevices() {
        PushDevices devices = new PushDevices(store, sender, null);
        for (int i = 0; i < 12; i++) {
            devices.register(WALLET, new PushDevices.Device(TOPIC, String.format("%064x", i), "production")).join();
        }
        List<PushDevices.Device> kept = devices.listForTest(WALLET);
        assertEquals(10, kept.size());
        assertEquals(String.format("%064x", 11), kept.get(0).token(), "newest first");
        assertFalse(kept.stream().anyMatch(d -> d.token().equals(String.format("%064x", 0))), "oldest dropped");
    }

    @Test
    void devicesAndNotifyAreChecked() {
        assertTrue(PushDevices.parse(Map.of("token", "zz", "topic", TOPIC, "environment", "sandbox")) instanceof String);
        assertTrue(PushDevices.parse(Map.of("token", "ab".repeat(32), "topic", TOPIC, "environment", "dev")) instanceof String);
        assertTrue(PushDevices.parse(Map.of("token", "AB".repeat(32), "topic", TOPIC, "environment", "production"))
                instanceof PushDevices.Device);
        assertTrue(ConsortiumHandler.parseNotify(Map.of("topic", TOPIC)) instanceof String, "a title is required");
        assertTrue(ConsortiumHandler.parseNotify(Map.of("topic", TOPIC, "title", "t",
                "data", Map.of("x", "y".repeat(2000)))) instanceof String, "data is capped");
        assertTrue(ConsortiumHandler.parseNotify(chapterNotify()) instanceof Map);
    }
}
