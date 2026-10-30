package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PhoneUserDataTest {

    private final AnyphoneConfig config = AnyphoneConfig.disabled(0.009);

    @Test
    void bundledTemplateWritesTheHostedStackEnvAndRunsTheBootstrap() {
        String script = PhoneUserData.render(PhoneUserData.template(config),
                PhoneUserData.values(config, "ph-0123456789abcdef", "tok_EN-abc123"));
        assertTrue(script.startsWith("#!/bin/bash\n"));
        assertTrue(script.contains("cat > /etc/anyphone/stack.env"));
        assertTrue(script.contains("\nANYPHONE_HOSTED=1\n"));
        assertTrue(script.contains("\nANYPHONE_TOKEN=tok_EN-abc123\n"));
        assertTrue(script.contains("\nANYPHONE_PHONE_ID=ph-0123456789abcdef\n"));
        assertTrue(script.contains("\nANYPHONE_VERSION=main\n"));
        assertTrue(script.contains("\nANYPHONE_REPO=tapacuk93/anyphone\n"));
        assertTrue(script.contains("\nAWS_REGION=us-east-1\n"));
        assertTrue(script.contains("/usr/local/sbin/anyphone-bootstrap --install-unit"));
        assertFalse(script.contains("{{"), "every placeholder is filled");
    }

    @Test
    void refusesAValueThatIsNotShellSafe() {
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render("X={{ANYPHONE_TOKEN}}",
                Map.of("ANYPHONE_TOKEN", "a'; rm -rf / #")));
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render("X={{ANYPHONE_TOKEN}}",
                Map.of("ANYPHONE_TOKEN", "$(id)")));
    }

    @Test
    void refusesAnUnknownPlaceholderRatherThanLeavingItBlank() {
        assertThrows(IllegalArgumentException.class, () -> PhoneUserData.render("X={{NOPE}}",
                Map.of("ANYPHONE_TOKEN", "abc")));
    }
}
