package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * The China mainland storefront: OpenAI is never routed to, and naming it is a 451 with nothing
 * charged. Apple rejects an app on that store otherwise (Guideline 5), and the proxy — not the app —
 * picks the provider for automatic routing, panels, escalation, failover, images and speech.
 */
class StorefrontTest {

    private static final Map<String, Set<String>> DEFAULTS = Storefront.DEFAULT_RESTRICTIONS;

    private static ProxyConfig configWith(String... envPairs) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i + 1 < envPairs.length; i += 2) {
            env.put(envPairs[i], envPairs[i + 1]);
        }
        return ProxyConfig.load(env);
    }

    private static ProxyConfig everyKey(String... extra) {
        String[] keys = {
                "AICOIN_PROXY_OPENAI_APIKEY", "o",
                "AICOIN_PROXY_ANTHROPIC_APIKEY", "a",
                "AICOIN_PROXY_GOOGLE_APIKEY", "g",
                "AICOIN_PROXY_MISTRAL_APIKEY", "m",
                "AICOIN_PROXY_KIMI_APIKEY", "k",
                "AICOIN_PROXY_STABILITY_APIKEY", "s",
                "AICOIN_PROXY_ELEVENLABS_APIKEY", "e"};
        String[] all = new String[keys.length + extra.length];
        System.arraycopy(keys, 0, all, 0, keys.length);
        System.arraycopy(extra, 0, all, keys.length, extra.length);
        return configWith(all);
    }

    private static final Storefront CHINA = Storefront.fromHeader("CHN", DEFAULTS);

    // ---- the header ----

    @Test
    void noHeaderMeansNoRestriction() {
        assertSame(Storefront.NONE, Storefront.fromHeader(null, DEFAULTS));
        assertSame(Storefront.NONE, Storefront.fromHeader("  ", DEFAULTS));
        assertTrue(Storefront.NONE.allows("openai"));
        assertFalse(Storefront.NONE.isRestricted());
    }

    @Test
    void aMalformedHeaderIsTreatedAsNoHeader() {
        assertSame(Storefront.NONE, Storefront.fromHeader("CN", DEFAULTS));
        assertSame(Storefront.NONE, Storefront.fromHeader("CHINA", DEFAULTS));
        assertSame(Storefront.NONE, Storefront.fromHeader("C1N", DEFAULTS));
    }

    @Test
    void chinaWithholdsOpenAiAndNothingElse() {
        assertTrue(CHINA.isRestricted());
        assertFalse(CHINA.allows("openai"));
        assertFalse(CHINA.allows("OpenAI"));
        for (String other : List.of("anthropic", "google", "mistral", "kimi", "stability", "elevenlabs", "googletts")) {
            assertTrue(CHINA.allows(other), other);
        }
        // The code is read case-insensitively and trimmed, as StoreKit's is not guaranteed to be.
        assertFalse(Storefront.fromHeader(" chn ", DEFAULTS).allows("openai"));
    }

    @Test
    void otherStorefrontsAreUnrestricted() {
        Storefront usa = Storefront.fromHeader("USA", DEFAULTS);
        assertEquals("USA", usa.code());
        assertTrue(usa.allows("openai"));
        assertFalse(usa.isRestricted());
        assertEquals(List.of("openai", "anthropic"), usa.filter(List.of("openai", "anthropic")));
    }

    @Test
    void filteringKeepsTheOrderSoTheNextBestMovesUp() {
        assertEquals(List.of("anthropic", "google"), CHINA.filter(List.of("openai", "anthropic", "google")));
        assertEquals(List.of(), CHINA.filter(List.of("openai")));
    }

    @Test
    void theRefusalHasTheProxysErrorShape() {
        Map<?, ?> error = (Map<?, ?>) new Yaml().load(CHINA.errorJson("openai"));
        assertEquals("provider_unavailable_in_region", error.get("error"));
        assertEquals("openai", error.get("provider"));
        assertEquals("CHN", error.get("storefront"));
        assertTrue(error.get("message") instanceof String);
    }

    // ---- config ----

    @Test
    void theDefaultIsOpenAiOnTheChinaStorefront() {
        assertEquals(Set.of("openai"), configWith().getStorefrontRestrictions().get("CHN"));
        assertEquals(Set.of("CHN"), Storefront.restrictingCodes(configWith()));
    }

    @Test
    void envOverridesOrClearsAStorefrontsList() {
        ProxyConfig more = configWith("AICOIN_PROXY_STOREFRONT_CHN_RESTRICTED", "openai, Kimi");
        assertEquals(Set.of("openai", "kimi"), more.getStorefrontRestrictions().get("CHN"));
        assertFalse(Storefront.fromHeader("CHN", more).allows("kimi"));

        ProxyConfig none = configWith("AICOIN_PROXY_STOREFRONT_CHN_RESTRICTED", "none");
        assertTrue(Storefront.fromHeader("CHN", none).allows("openai"));
        assertTrue(Storefront.restrictingCodes(none).isEmpty());

        ProxyConfig added = configWith("AICOIN_PROXY_STOREFRONT_RUS_RESTRICTED", "anthropic");
        assertFalse(Storefront.fromHeader("RUS", added).allows("anthropic"));
        assertFalse(Storefront.fromHeader("CHN", added).allows("openai"), "adding one keeps the default");
    }

    @Test
    void yamlListsAndStringsBothParse() {
        Map<String, Set<String>> parsed = Storefront.parseYaml(Map.of(
                "chn", List.of("openai", "Mistral"), "RUS", "anthropic,google", "bad1", "openai"));
        assertEquals(Set.of("openai", "mistral"), parsed.get("CHN"));
        assertEquals(Set.of("anthropic", "google"), parsed.get("RUS"));
        assertEquals(2, parsed.size());
    }

    // ---- automatic routing: text, images, speech, failover, escalation ----

    @Test
    void textRoutingSkipsOpenAiAndKeepsTheRest() {
        ProxyConfig config = everyKey();
        List<String> everywhere = CapabilityHandler.candidates(config, null, null, Capability.TEXT, "math", false,
                Storefront.NONE);
        assertTrue(everywhere.contains("openai"), "precondition: openai is a text candidate with a key");
        List<String> china = CapabilityHandler.candidates(config, null, null, Capability.TEXT, "math", false, CHINA);
        assertFalse(china.contains("openai"));
        // The rest in the same order: the failover chain and the escalation panel are this list.
        assertEquals(CHINA.filter(everywhere), china);
        assertFalse(china.isEmpty());
    }

    @Test
    void fastestRoutingSkipsOpenAiToo() {
        List<String> china = CapabilityHandler.candidates(everyKey(), null, null, Capability.TEXT, "code", true, CHINA);
        assertFalse(china.contains("openai"));
        assertFalse(china.isEmpty());
    }

    @Test
    void imagesGoToTheNextImageProviderNotToOpenAi() {
        ProxyConfig config = everyKey();
        List<String> everywhere = CapabilityHandler.candidates(config, null, null, Capability.IMAGE, "creative",
                false, Storefront.NONE);
        assertTrue(everywhere.contains("openai"), "precondition: gpt-image is configured");
        List<String> china = CapabilityHandler.candidates(config, null, null, Capability.IMAGE, "creative", false, CHINA);
        assertFalse(china.contains("openai"));
        assertTrue(china.contains("stability"));
    }

    @Test
    void speechGoesToTheNextVoiceNotToOpenAi() {
        ProxyConfig config = everyKey();
        List<String> everywhere = CapabilityHandler.candidates(config, null, null, Capability.AUDIO, null,
                false, Storefront.NONE);
        assertTrue(everywhere.contains("openai"), "precondition: OpenAI TTS is configured");
        List<String> china = CapabilityHandler.candidates(config, null, null, Capability.AUDIO, null, false, CHINA);
        assertFalse(china.contains("openai"));
        assertFalse(china.isEmpty());
    }

    @Test
    void pinningOpenAiOnACapabilityIsA451BeforeAnythingIsSpent() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        Storefront.set(channel, CHINA);
        // No ledger, no client group: a 451 that touched either would throw instead.
        CapabilityHandler.serve(channel.pipeline().firstContext(), Capability.IMAGE,
                "{\"prompt\":\"a cat\",\"provider\":\"openai\"}".getBytes(CharsetUtil.UTF_8),
                everyKey(), null, null, null, null, null, "wallet");
        assertRefused(channel.readOutbound(), "openai");
    }

    // ---- consortium: panel, editor ----

    @Test
    void openAiIsNeverSeatedOnAChinaPanel() {
        ProxyConfig config = everyKey();
        List<String> panel = CHINA.filter(ConsortiumHandler.panel(config, null, "math", null, false));
        assertFalse(panel.contains("openai"));
        assertFalse(panel.isEmpty());
        // With openai gone the editor is someone else, even if config names openai as the editor.
        ProxyConfig openAiEdits = everyKey("AICOIN_PROXY_CONSORTIUM_EDITOR", "openai");
        String editor = ConsortiumHandler.editor(openAiEdits,
                CHINA.filter(ConsortiumHandler.panel(openAiEdits, null, "math", null, false)), null);
        assertTrue(editor != null && !editor.equals("openai"), "editor " + editor);
    }

    @Test
    void namingOpenAiAsEditorOrSolePanelistIsAPin() {
        assertEquals("openai", ConsortiumHandler.restrictedPin(CHINA, null, "OpenAI"));
        assertEquals("openai", ConsortiumHandler.restrictedPin(CHINA, Set.of("openai"), null));
        // A list that also names allowed providers narrows; openai just drops out.
        assertNull(ConsortiumHandler.restrictedPin(CHINA, Set.of("openai", "anthropic"), null));
        assertNull(ConsortiumHandler.restrictedPin(CHINA, null, "anthropic"));
        assertNull(ConsortiumHandler.restrictedPin(Storefront.NONE, Set.of("openai"), "openai"));
    }

    @Test
    void aConsortiumNamingOpenAiAsEditorIsA451() {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        Storefront.set(channel, CHINA);
        ConsortiumHandler.serve(channel.pipeline().firstContext(),
                "{\"prompt\":\"hi\",\"editor\":\"openai\"}".getBytes(CharsetUtil.UTF_8),
                everyKey(), null, null, null, "wallet");
        assertRefused(channel.readOutbound(), "openai");
    }

    // ---- the X-AI route ----

    private static FullHttpRequest xAi(String provider, String storefront) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/v1/images/generations", Unpooled.copiedBuffer("{}", CharsetUtil.UTF_8));
        request.headers().set("X-AI", provider);
        if (storefront != null) {
            request.headers().set(Storefront.HEADER, storefront);
        }
        return request;
    }

    @Test
    void xAiOpenAiFromChinaIsA451() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new ProxyFrontendHandler(everyKey(), null, null, null, null, null));
        channel.writeInbound(xAi("openai", "CHN"));
        assertRefused(channel.readOutbound(), "openai");
    }

    @Test
    void xAiOpenAiWithoutTheHeaderIsServedAsBefore() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new ProxyFrontendHandler(everyKey(), null, null, null, null, null));
        channel.writeInbound(xAi("openai", null));
        FullHttpResponse response = channel.readOutbound();
        // Past the storefront check, and stopped by the next one: no API token.
        assertEquals(401, response.status().code());
        response.release();
        // And the storefront of one request does not stick to the next on the same connection.
        channel.writeInbound(xAi("openai", "CHN"));
        assertRefused(channel.readOutbound(), "openai");
        channel.writeInbound(xAi("openai", null));
        FullHttpResponse after = channel.readOutbound();
        assertEquals(401, after.status().code());
        after.release();
    }

    @Test
    void xAiAnotherProviderFromChinaIsNotRefused() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new ProxyFrontendHandler(everyKey(), null, null, null, null, null));
        channel.writeInbound(xAi("stability", "CHN"));
        FullHttpResponse response = channel.readOutbound();
        assertEquals(401, response.status().code());
        response.release();
    }

    // ---- GET /skills ----

    @Test
    void skillsDescribesWhatTheCallersStorefrontWouldBeRoutedTo() {
        ProxyConfig config = everyKey();
        assertTrue(SkillsHandler.buildJson(config, null, null, Storefront.NONE).contains("\"openai\""));
        String china = SkillsHandler.buildJson(config, null, null, CHINA);
        assertFalse(china.contains("\"openai"), china);
        assertTrue(china.contains("\"anthropic\""));
    }

    // ---- the access log ----

    @Test
    void aRestrictedRequestCarriesItsStorefrontInTheAccessLog(@TempDir Path dir) throws Exception {
        ProxyConfig config = configWith("AICOIN_PROXY_ACCESS_LOG_PATH", dir.resolve("access.log").toString(),
                "AICOIN_PROXY_OPENAI_APIKEY", "o");
        AccessLog log = AccessLog.create(config);
        EmbeddedChannel channel = new EmbeddedChannel(
                new AccessLogHandler(log, Storefront.restrictingCodes(config)),
                new ProxyFrontendHandler(config, null, null, null, null, null));
        channel.writeInbound(xAi("openai", "CHN"));
        channel.writeInbound(xAi("openai", "USA"));
        channel.writeInbound(xAi("openai", null));
        log.close();

        List<String> lines;
        try (Stream<Path> files = Files.list(dir)) {
            Path file = files.filter(p -> p.getFileName().toString().startsWith("access.log")).findFirst().orElseThrow();
            lines = Files.readAllLines(file);
        }
        assertEquals(3, lines.size(), lines.stream().collect(Collectors.joining("\n")));
        Map<?, ?> china = (Map<?, ?>) new Yaml().load(lines.get(0));
        assertEquals("CHN", china.get("storefront"));
        assertEquals(451, china.get("status"));
        assertEquals("openai", china.get("provider"));
        assertNull(((Map<?, ?>) new Yaml().load(lines.get(1))).get("storefront"), "USA restricts nothing");
        assertNull(((Map<?, ?>) new Yaml().load(lines.get(2))).get("storefront"));
    }

    private static void assertRefused(Object outbound, String provider) {
        assertTrue(outbound instanceof FullHttpResponse, "expected a response, got " + outbound);
        FullHttpResponse response = (FullHttpResponse) outbound;
        try {
            assertEquals(451, response.status().code());
            assertEquals("application/json", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
            Map<?, ?> body = (Map<?, ?>) new Yaml().load(response.content().toString(CharsetUtil.UTF_8));
            assertEquals("provider_unavailable_in_region", body.get("error"));
            assertEquals(provider, body.get("provider"));
            assertEquals("CHN", body.get("storefront"));
            // Nothing was charged: no X-Aicoin-Charged, as on every pre-flight refusal.
            assertNull(response.headers().get("X-Aicoin-Charged"));
        } finally {
            response.release();
        }
    }
}
