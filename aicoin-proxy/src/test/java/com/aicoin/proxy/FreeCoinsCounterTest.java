package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * freeCoins.counterFile reads, per CONTRACT.md's "Additional proxy-side
 * endpoints" section: reads fresh on every call; missing/unparseable file
 * resolves to 0.
 */
class FreeCoinsCounterTest {

    @Test
    void bundledDefaultResourceIsZero() {
        assertEquals(0, FreeCoinsCounter.readAvailable("free-coins-counter.txt"));
    }

    @Test
    void readsIntegerFromFilesystemPath(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("counter.txt");
        Files.write(file, "7".getBytes(StandardCharsets.UTF_8));
        assertEquals(7, FreeCoinsCounter.readAvailable(file.toString()));
    }

    @Test
    void reflectsChangesOnEachCall(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("counter.txt");
        Files.write(file, "1".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, FreeCoinsCounter.readAvailable(file.toString()));

        Files.write(file, "5".getBytes(StandardCharsets.UTF_8));
        assertEquals(5, FreeCoinsCounter.readAvailable(file.toString()));
    }

    @Test
    void missingFileResolvesToZero() {
        assertEquals(0, FreeCoinsCounter.readAvailable("/no/such/path/counter.txt"));
    }

    @Test
    void unparseableContentResolvesToZero(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("counter.txt");
        Files.write(file, "not-a-number".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, FreeCoinsCounter.readAvailable(file.toString()));
    }

    @Test
    void nullCounterFileResolvesToZero() {
        assertEquals(0, FreeCoinsCounter.readAvailable(null));
    }
}
