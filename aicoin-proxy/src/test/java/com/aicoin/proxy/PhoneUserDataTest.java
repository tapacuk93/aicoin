package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneUserDataTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    private static final String RELEASES = "https://releases.example.com/releases";

    private final AnyphoneConfig config = new AnyphoneConfig(true, "us-east-1", "", true, "s", "g", 40, "v0.2.0",
            RELEASES, "", "com.oeaio.anyphone", 0.009, 0.5, 10, 5, 180, 15, 300, AnyphoneConfig.defaultTiers());

    private static List<String> stackEnv(String script) {
        int start = script.indexOf("cat > /etc/anyphone/stack.env <<'EOF'\n");
        int end = script.indexOf("\nEOF\n", start);
        return List.of(script.substring(script.indexOf('\n', start) + 1, end).split("\n"));
    }

    @Test
    void bundledScriptWritesExactlyTheHostedStackEnv() {
        String script = PhoneUserData.render(PhoneUserData.template(config),
                PhoneUserData.values(config, "ph-0123456789abcdef", TOKEN, false), RELEASES);
        assertTrue(script.startsWith("#!/bin/bash\n"));
        assertEquals(List.of(
                "ANYPHONE_HOSTED=1",
                "ANYPHONE_TOKEN=" + TOKEN,
                "ANYPHONE_PHONE_ID=ph-0123456789abcdef",
                "ANYPHONE_VERSION=v0.2.0",
                "ANYPHONE_REPO=" + RELEASES,
                "AWS_REGION=us-east-1",
                "ANYPHONE_RESTORE=",
                "ANYPHONE_IDLE_MINUTES=0"), stackEnv(script));
        assertFalse(script.contains("anyphone-cfn-k7q2m9"), "the deleted default bucket is never used");
        assertFalse(script.contains("github"));
        assertTrue(script.contains("/usr/local/sbin/anyphone-bootstrap --install-unit"));
    }

    @Test
    void restoreSetsExpected() {
        String script = PhoneUserData.render(PhoneUserData.template(config),
                PhoneUserData.values(config, "ph-1", TOKEN, true), RELEASES);
        assertTrue(stackEnv(script).contains("ANYPHONE_RESTORE=expected"));
    }

    @Test
    void refusesUnsafeValuesShortTokensAndAMissingReleaseUrl() {
        Map<String, String> values = new LinkedHashMap<>(PhoneUserData.values(config, "ph-1", TOKEN, false));
        String template = PhoneUserData.template(config);
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render(template, values, ""));
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render(template, values, "http://x/y"));
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render(template, values, "https://x/a b"));
        values.put("__TOKEN__", "short");
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render(template, values, RELEASES));
        values.put("__TOKEN__", TOKEN);
        values.put("__VERSION__", "v1; rm -rf /");
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render(template, values, RELEASES));
        values.put("__VERSION__", "v1\nEVIL=1");
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render(template, values, RELEASES));
    }

    @Test
    void refusesALeftoverPlaceholderOrNoRepoLine() {
        Map<String, String> values = PhoneUserData.values(config, "ph-1", TOKEN, false);
        assertThrows(IllegalArgumentException.class,
                () -> PhoneUserData.render("ANYPHONE_REPO=x\nX=__NEW_THING__\n", values, RELEASES));
        assertThrows(IllegalArgumentException.class,
                () -> PhoneUserData.render("ANYPHONE_TOKEN=__TOKEN__\n", values, RELEASES));
    }
}
