package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Json#load}, the parser every JSON body goes through. It replaced SnakeYAML, which
 * answered 400 "body must be JSON" to any iOS prompt containing a slash: Apple's JSONSerialization
 * writes "/" as {@code \/}, a JSON escape YAML does not have.
 */
class JsonLoadTest {

    private static Map<?, ?> map(String json) {
        return (Map<?, ?>) Json.load(json);
    }

    @Test
    void slashEscapeIsASlash() {
        assertEquals("a/b/c", map("{\"p\":\"a\\/b\\/c\"}").get("p"));
        assertEquals("https://x.y/z", map("{\"u\":\"https:\\/\\/x.y\\/z\"}").get("u"));
    }

    @Test
    void everyJsonEscapeDecodes() {
        assertEquals("\" \\ / \b \f \n \r \t", map("{\"s\":\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"}").get("s"));
    }

    @Test
    void surrogatePairEscapesBecomeTheOneCharacter() {
        String grin = new String(Character.toChars(0x1F600));
        assertEquals(grin, map("{\"e\":\"\\ud83d\\ude00\"}").get("e"));
        assertEquals("hi " + grin, map("{\"e\":\"hi \\uD83D\\uDE00\"}").get("e"));
        // Raw (unescaped) emoji too.
        assertEquals(grin, map("{\"e\":\"" + grin + "\"}").get("e"));
    }

    @Test
    void unicodeSurvivesEscapedAndRaw() {
        assertEquals("caf\u00e9 \u4e2d\u6587", map("{\"s\":\"caf\\u00e9 \\u4e2d\\u6587\"}").get("s"));
        assertEquals("caf\u00e9 \u4e2d\u6587", map("{\"s\":\"caf\u00e9 \u4e2d\u6587\"}").get("s"));
        // SnakeYAML folded a raw NEL (U+0085) inside a string into a space.
        assertEquals("a\u0085b\u2028c", map("{\"s\":\"a\u0085b\u2028c\"}").get("s"));
        assertEquals("\u0000", map("{\"s\":\"\\u0000\"}").get("s"));
    }

    @Test
    void nestedStructuresKeepTheirShapeAndOrder() {
        Map<?, ?> root = map(" {\"a\": [1, {\"b\": [true, false, null]}, \"x\"], \"c\": {}, \"d\": [] } ");
        List<?> a = (List<?>) root.get("a");
        assertEquals(3, a.size());
        assertEquals(1, a.get(0));
        assertEquals(List.of(true, false), ((List<?>) ((Map<?, ?>) a.get(1)).get("b")).subList(0, 2));
        assertNull(((List<?>) ((Map<?, ?>) a.get(1)).get("b")).get(2));
        assertTrue(root.containsKey("c") && ((Map<?, ?>) root.get("c")).isEmpty());
        assertEquals(List.of(), root.get("d"));
        assertEquals(List.of("a", "c", "d"), List.copyOf(root.keySet()));
        assertEquals(List.of(1, 2), Json.load("[1,2]"));
    }

    @Test
    void numbersComeBackAsTheTypesSnakeYamlGave() {
        Map<?, ?> m = map("{\"i\":42,\"n\":-7,\"l\":12345678901,\"big\":123456789012345678901234,"
                + "\"d\":1.5,\"e\":1e3,\"E\":-2.5E-2,\"z\":0,\"max\":2147483647,\"over\":2147483648}");
        assertEquals(Integer.valueOf(42), m.get("i"));
        assertEquals(Integer.valueOf(-7), m.get("n"));
        assertEquals(Long.valueOf(12345678901L), m.get("l"));
        assertEquals(new BigInteger("123456789012345678901234"), m.get("big"));
        assertEquals(Double.valueOf(1.5), m.get("d"));
        assertEquals(Double.valueOf(1000.0), m.get("e"));
        assertEquals(Double.valueOf(-0.025), m.get("E"));
        assertEquals(Integer.valueOf(0), m.get("z"));
        assertInstanceOf(Integer.class, m.get("max"));
        assertInstanceOf(Long.class, m.get("over"));
        assertEquals(Long.MAX_VALUE, Json.load(Long.toString(Long.MAX_VALUE)));
    }

    @Test
    void repeatedKeyKeepsTheLastValue() {
        assertEquals(2, map("{\"a\":1,\"a\":2}").get("a"));
    }

    @Test
    void bigBodiesParse() {
        // Past SnakeYAML's 3 MiB code-point limit, which refused long prompts and image responses.
        StringBuilder sb = new StringBuilder("{\"p\":\"");
        for (int i = 0; i < 1_000_000; i++) {
            sb.append("ab\\/c");
        }
        sb.append("\"}");
        String p = (String) map(sb.toString()).get("p");
        assertEquals(4_000_000, p.length());
        assertTrue(p.startsWith("ab/cab/c"));
    }

    @Test
    void blankOrNullIsNullAsBefore() {
        assertNull(Json.load(null));
        assertNull(Json.load(""));
        assertNull(Json.load("  \n"));
        assertNull(Json.load("null"));
    }

    @Test
    void malformedThrows() {
        for (String bad : new String[] {
                "{", "}", "{\"a\":}", "{\"a\" 1}", "{a:1}", "{'a':1}", "[1,]", "{\"a\":1,}", "[1 2]",
                "\"unterminated", "\"bad \\x escape\"", "\"\\u12\"", "\"\\u12G4\"", "\"raw\nnewline\"",
                "01", "-", "1.", ".5", "1e", "+1", "tru", "nul", "NaN", "Infinity", "{} {}", "[1] x",
                "a: b", "- 1",
        }) {
            assertThrows(IllegalArgumentException.class, () -> Json.load(bad), bad);
        }
    }

    @Test
    void absurdNestingIsRefusedNotAStackOverflow() {
        String deep = "[".repeat(100_000) + "]".repeat(100_000);
        assertThrows(IllegalArgumentException.class, () -> Json.load(deep));
        String ok = "[".repeat(500) + "]".repeat(500);
        assertInstanceOf(List.class, Json.load(ok));
    }

    @Test
    void roundTripsWhatJsonStringWrites() {
        String awkward = "q\" b\\ n\n t\t c\u0001 ls\u2028 / \uD83D\uDE00";
        assertEquals(awkward, map("{\"v\":" + Json.string(awkward) + "}").get("v"));
    }

    // --- POST /text end to end, up to the first check that needs a provider ---

    private static FullHttpResponse serve(String body) {
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        CapabilityHandler.serve(channel.pipeline().firstContext(), Capability.TEXT,
                body.getBytes(CharsetUtil.UTF_8), null, null, null, null, null, null, "wallet");
        return channel.readOutbound();
    }

    @Test
    void capabilityBodyWithSlashEscapesIsReadAsJson() {
        // An unknown subject is refused before any provider is consulted, so getting that error
        // (rather than "body must be JSON") proves the body — "\/" and all — was parsed.
        FullHttpResponse response = serve(
                "{\"prompt\":\"what is 1\\/2 + 1\\/3? \\ud83d\\ude00\",\"subject\":\"no\\/such\"}");
        String text = response.content().toString(CharsetUtil.UTF_8);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(text.contains("subject must be one of"), text);
        response.release();
    }

    @Test
    void capabilityBodyLargerThanSnakeYamlsLimitReachesTheLengthCheck() {
        StringBuilder sb = new StringBuilder("{\"prompt\":\"");
        for (int i = 0; i < 700_000; i++) {
            sb.append("\\u00e9");
        }
        sb.append("\"}");
        FullHttpResponse response = serve(sb.toString());
        assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, response.status());
        response.release();
    }

    @Test
    void capabilityBodyThatIsNotJsonIsStillRefused() {
        FullHttpResponse response = serve("prompt: hi");
        String text = response.content().toString(CharsetUtil.UTF_8);
        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        assertTrue(text.contains("body must be JSON"), text);
        response.release();
    }
}
