package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * Exercises the access log's real file behaviour — that it writes parseable lines, that rotation
 * actually bounds disk, and that it degrades to off rather than taking the proxy down with it.
 */
class AccessLogTest {

    private static Map<String, String> env(Path path, String maxBytes, String count) {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ACCESS_LOG_PATH", path.toString());
        env.put("AICOIN_PROXY_ACCESS_LOG_MAX_BYTES", maxBytes);
        env.put("AICOIN_PROXY_ACCESS_LOG_COUNT", count);
        return env;
    }

    /** Rotated files are {@code access.log.0}, {@code .1}, ... alongside JUL's {@code .lck}. */
    private static List<Path> logFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> !p.toString().endsWith(".lck")).sorted().toList();
        }
    }

    // ---- defaults ----

    @Test
    void accessLoggingIsOnByDefaultSoADeploymentActuallyGetsLogs() {
        ProxyConfig config = ProxyConfig.load(new HashMap<>());

        assertEquals("logs/access.log", config.getAccessLogPath());
        assertEquals(10 * 1024 * 1024, config.getAccessLogMaxBytes());
        assertEquals(5, config.getAccessLogCount());
    }

    /**
     * An env var cannot express "empty" — {@code envStr} treats an empty value as unset and falls
     * back to the default — so {@code none} is the sentinel that turns logging off from the
     * environment, matching the provider {@code FREEPATHS} keys. Setting the env var to the empty
     * string deliberately keeps the default on, which this pins so nobody documents otherwise.
     */
    @Test
    void noneTurnsItOffFromTheEnvironment() {
        Map<String, String> off = new HashMap<>();
        off.put("AICOIN_PROXY_ACCESS_LOG_PATH", "none");
        assertNull(AccessLog.create(ProxyConfig.load(off)),
                "'none' must disable logging, not create a file literally called none");

        Map<String, String> empty = new HashMap<>();
        empty.put("AICOIN_PROXY_ACCESS_LOG_PATH", "");
        assertEquals("logs/access.log", ProxyConfig.load(empty).getAccessLogPath(),
                "an empty env value is 'unset', so the default stands — use 'none' to disable");
    }

    // ---- writing ----

    @Test
    void writesOneParseableJsonObjectPerRequest(@TempDir Path dir) throws Exception {
        AccessLog log = AccessLog.create(ProxyConfig.load(env(dir.resolve("access.log"), "1048576", "3")));
        assertNotNull(log);

        log.record("POST", "/v1/text-to-speech/x/with-timestamps", "elevenlabs", "abc123",
                200, 52, 900_000, 8421, "1", "ok");
        log.close();

        List<String> lines = Files.readAllLines(logFiles(dir).get(0));
        assertEquals(1, lines.size());

        Object parsed = new Yaml().load(lines.get(0));
        assertTrue(parsed instanceof Map, "each line must stand alone as JSON: " + lines.get(0));
        Map<?, ?> entry = (Map<?, ?>) parsed;
        assertEquals("POST", entry.get("method"));
        assertEquals("/v1/text-to-speech/x/with-timestamps", entry.get("path"));
        assertEquals("elevenlabs", entry.get("provider"));
        assertEquals(200, entry.get("status"));
        assertEquals(8421, entry.get("duration_ms"));
        assertEquals("1", entry.get("coins"));
        assertEquals("ok", entry.get("outcome"));
        assertNotNull(entry.get("at"));
    }

    /**
     * The client-timed-out case: no status was ever produced, so it is recorded as {@code -1}.
     * This is the only trace such a request leaves anywhere — see {@link AccessLogHandler}.
     */
    @Test
    void recordsARequestThatNeverGotAResponse(@TempDir Path dir) throws Exception {
        AccessLog log = AccessLog.create(ProxyConfig.load(env(dir.resolve("access.log"), "1048576", "3")));

        log.record("POST", "/v1/text-to-speech/x/with-timestamps", "elevenlabs", "abc123",
                -1, 52, 0, 20_000, "", "client_gone");
        log.close();

        Map<?, ?> entry = (Map<?, ?>) new Yaml().load(Files.readAllLines(logFiles(dir).get(0)).get(0));
        assertEquals(-1, entry.get("status"));
        assertEquals("client_gone", entry.get("outcome"));
    }

    // ---- rotation ----

    @Test
    void rotationBoundsTotalDiskUsage(@TempDir Path dir) throws Exception {
        // Tiny files and a low count, so a few hundred records must roll over repeatedly.
        AccessLog log = AccessLog.create(ProxyConfig.load(env(dir.resolve("access.log"), "1024", "3")));

        for (int i = 0; i < 500; i++) {
            log.record("GET", "/price", "", "", 200, 0, 83, i, "", "ok");
        }
        log.close();

        List<Path> files = logFiles(dir);
        assertEquals(3, files.size(), "count=3 must cap the number of files kept: " + files);
        long total = 0;
        for (Path f : files) {
            total += Files.size(f);
        }
        // 500 records at ~130 bytes each is ~65KB; the cap must have thrown most of it away.
        assertTrue(total <= 3 * 1024 + 512, "rotation must bound total bytes, got " + total);
        // Whatever survived must still be complete, parseable lines — not a truncated tail.
        for (String line : Files.readAllLines(files.get(0))) {
            assertTrue(new Yaml().load(line) instanceof Map, "rotation left a partial line: " + line);
        }
    }

    // ---- failure posture ----

    @Test
    void anUnwritablePathDisablesLoggingRatherThanThrowing(@TempDir Path dir) throws Exception {
        // A path whose parent is a regular file can't be created as a directory.
        Path file = dir.resolve("iam-a-file");
        Files.writeString(file, "x");

        assertNull(AccessLog.create(ProxyConfig.load(env(file.resolve("nested/access.log"), "1024", "2"))),
                "a broken log destination must never stop the proxy from serving traffic");
    }

    // ---- escaping ----

    @Test
    void escapesValuesThatWouldOtherwiseBreakTheLine() {
        assertEquals("\"a\\\"b\"", AccessLog.json("a\"b"));
        assertEquals("\"a\\\\b\"", AccessLog.json("a\\b"));
        assertEquals("\"a\\nb\"", AccessLog.json("a\nb"));
        assertEquals("\"\\u0007\"", AccessLog.json("\u0007"));
        assertEquals("\"\"", AccessLog.json(null));
    }

    /** A crafted path must not be able to forge extra log entries or break the one it's in. */
    @Test
    void aCraftedPathCannotForgeALogLine(@TempDir Path dir) throws Exception {
        AccessLog log = AccessLog.create(ProxyConfig.load(env(dir.resolve("access.log"), "1048576", "3")));

        log.record("GET", "/x\",\"status\":999}\n{\"forged\":true", "", "", 200, 0, 0, 1, "", "ok");
        log.close();

        List<String> lines = Files.readAllLines(logFiles(dir).get(0));
        assertEquals(1, lines.size(), "an injected newline must not become a second line");
        Map<?, ?> entry = (Map<?, ?>) new Yaml().load(lines.get(0));
        assertEquals(200, entry.get("status"), "the real status must survive the injection attempt");
        assertFalse(entry.containsKey("forged"));
    }
}
