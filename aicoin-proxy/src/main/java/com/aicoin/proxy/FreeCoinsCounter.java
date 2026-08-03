package com.aicoin.proxy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads {@code freeCoins.counterFile} fresh on every call, per CONTRACT.md's
 * "Additional proxy-side endpoints" section: a single integer, bundled as a
 * classpath resource by default (manually bumped by an operator via git
 * push + CI redeploy of the proxy), but also resolvable as a plain
 * filesystem path (used by tests / a locally-overridden deployment). Missing
 * or unparseable file resolves to {@code 0}.
 */
public final class FreeCoinsCounter {

    private FreeCoinsCounter() {
    }

    public static int readAvailable(String counterFile) {
        try {
            String content = readFresh(counterFile);
            if (content == null) {
                return 0;
            }
            return Integer.parseInt(content.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readFresh(String counterFile) throws IOException {
        if (counterFile == null || counterFile.isEmpty()) {
            return null;
        }
        File file = new File(counterFile);
        if (file.isFile()) {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        try (InputStream in = FreeCoinsCounter.class.getClassLoader().getResourceAsStream(counterFile)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
