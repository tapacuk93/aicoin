package com.aicoin.proxy;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * One line per request, written to a size-rotated file — the thing whose absence makes a report
 * like "the ElevenLabs request timed out" unanswerable after the fact.
 *
 * <p>Format is <b>JSON Lines</b>: one self-describing object per line, so a line survives being
 * grepped out of context and the whole file can be fed to {@code jq} (already a dependency of the
 * admin scripts) without a parser. Fields:
 *
 * <pre>
 * {"at":"2026-08-12T17:04:05.123Z","method":"POST","path":"/v1/text-to-speech/xxx/with-timestamps",
 *  "provider":"elevenlabs","wallet":"a1b2...","status":200,"req_bytes":412,"resp_bytes":1048576,
 *  "duration_ms":8421,"coins":"1","outcome":"ok"}
 * </pre>
 *
 * <p><b>What is deliberately never logged:</b> the {@code X-Api-Key} token itself (it can spend the
 * wallet's coins, so a log file that leaks is otherwise a wallet that leaks), any provider API key,
 * every request/response body, and the query string — Google's credential is injected as a query
 * parameter, and a redaction rule that has to stay correct as providers change is a worse bet than
 * simply never writing the query. The wallet <i>address</i> is logged in full: it is public by
 * design (the admin page lists addresses) and it is the only way to attribute usage.
 *
 * <p><b>Rotation</b> is {@link FileHandler}'s own size-based scheme — {@code accessLog.maxBytes}
 * per file, {@code accessLog.count} files, oldest overwritten. Bounded total disk is the point:
 * this runs on a $5 Lightsail box with a 20GB disk shared with Redis snapshots, so an unbounded
 * log is a way to take the service down. Defaults cap it at 50MB.
 *
 * <p><b>Writes happen off the event loop.</b> {@code FileHandler} does synchronous file I/O, and
 * doing that on a Netty event loop would stall every other connection that thread serves whenever
 * the disk hiccups or a rotation happens. Records are handed to a single daemon thread with a
 * bounded queue; if that queue ever fills (disk far slower than traffic), records are dropped and
 * counted rather than allowed to back up into request handling. Losing log lines is strictly
 * better than losing the service that produces them.
 */
final class AccessLog implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(AccessLog.class.getName());

    /** Bounded so a slow disk can never turn into unbounded heap growth. */
    private static final int QUEUE_CAPACITY = 4096;

    private final Logger fileLogger;
    private final FileHandler handler;
    private final ExecutorService writer;
    private long dropped;

    private AccessLog(Logger fileLogger, FileHandler handler, ExecutorService writer) {
        this.fileLogger = fileLogger;
        this.handler = handler;
        this.writer = writer;
    }

    /**
     * @return a live access log, or {@code null} when {@code accessLog.path} is empty (logging
     * off). A path whose directory cannot be created or opened is reported and treated as off —
     * an unwritable log must never stop the proxy from serving traffic.
     */
    static AccessLog create(ProxyConfig config) {
        String path = config.getAccessLogPath();
        // "none" as well as empty, because an env var cannot express "empty" here: ProxyConfig's
        // envStr treats an empty value as unset and falls back to the YAML default, so
        // AICOIN_PROXY_ACCESS_LOG_PATH="" would silently keep logging on. Same sentinel the
        // provider FREEPATHS keys already use for "deliberately nothing".
        if (path == null || path.isEmpty() || path.equalsIgnoreCase("none")) {
            LOG.info("access log disabled (accessLog.path is empty or 'none')");
            return null;
        }
        try {
            File file = new File(path).getAbsoluteFile();
            File dir = file.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                throw new IOException("could not create log directory " + dir);
            }
            // "%g" is FileHandler's generation number: access.log, access.log.1, ... Appending
            // matters so a restart continues the current file instead of truncating it.
            FileHandler handler = new FileHandler(
                    file.getPath() + ".%g", config.getAccessLogMaxBytes(), config.getAccessLogCount(), true);
            handler.setFormatter(new LineFormatter());
            handler.setLevel(Level.ALL);

            // A private, non-additive Logger: nothing here should reach the console handler that
            // carries the proxy's ordinary operational logging, and nothing there should land in
            // this file.
            Logger fileLogger = Logger.getLogger(AccessLog.class.getName() + ".file");
            fileLogger.setUseParentHandlers(false);
            fileLogger.setLevel(Level.ALL);
            fileLogger.addHandler(handler);

            ExecutorService writer = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                    r -> {
                        Thread t = new Thread(r, "aicoin-access-log");
                        t.setDaemon(true);
                        return t;
                    });
            LOG.info("access log writing to " + file.getPath() + ".{0.." + (config.getAccessLogCount() - 1) + "} "
                    + "(" + config.getAccessLogMaxBytes() + " bytes each)");
            return new AccessLog(fileLogger, handler, writer);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "could not open access log at " + path + " — access logging is off", e);
            return null;
        }
    }

    /**
     * Records one finished request. Never throws: a logging failure must not turn into a failed
     * response, so everything here is best-effort.
     *
     * @param status HTTP status written to the client, or {@code -1} when the client went away
     *               before any response was written (see {@code outcome})
     * @param coins  the {@code X-Aicoin-Charged} value, or empty when the call wasn't billed
     */
    void record(String method, String path, String provider, String wallet, int status,
                 int requestBytes, int responseBytes, long durationMillis, String coins, String outcome) {
        String line = "{\"at\":\"" + Instant.now() + "\""
                + ",\"method\":" + json(method)
                + ",\"path\":" + json(path)
                + ",\"provider\":" + json(provider)
                + ",\"wallet\":" + json(wallet)
                + ",\"status\":" + status
                + ",\"req_bytes\":" + requestBytes
                + ",\"resp_bytes\":" + responseBytes
                + ",\"duration_ms\":" + durationMillis
                + ",\"coins\":" + json(coins)
                + ",\"outcome\":" + json(outcome)
                + "}";
        try {
            writer.execute(() -> fileLogger.log(Level.INFO, line));
        } catch (Exception e) {
            // Queue full (or shutting down): count it and move on. Reported once per 1000 so a
            // sustained overrun is visible without the failure path becoming its own log flood.
            if (dropped++ % 1000 == 0) {
                LOG.warning("access log queue full — dropped " + dropped + " records so far");
            }
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            // Brief, bounded wait: give queued lines a chance to reach disk on a clean shutdown
            // without letting a stuck disk hold the process open.
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        handler.close();
    }

    /** The record's message, verbatim, one per line — no JUL preamble wrapping the JSON. */
    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return record.getMessage() + System.lineSeparator();
        }
    }

    /** Minimal JSON string escaping — enough for the controlled values written here. */
    static String json(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
