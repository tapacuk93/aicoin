package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * {@code GET /skills}. Routing a request on a caller's behalf is only defensible if the caller can
 * find out how, so what this publishes is the live ranking, not the table it came from.
 */
class SkillsHandlerTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return (Map<String, Object>) new Yaml().load(json);
    }

    @SuppressWarnings("unchecked")
    private static List<String> routing(Map<String, Object> body, String capability, String subject) {
        Map<String, Object> byCapability = (Map<String, Object>) body.get("routing");
        Map<String, Object> bySubject = (Map<String, Object>) byCapability.get(capability);
        return (List<String>) bySubject.get(subject);
    }

    @Test
    void publishesTheRankingThatWouldActuallyBeUsed() {
        Map<String, String> env = new HashMap<>();
        env.put("AICOIN_PROXY_ANTHROPIC_APIKEY", "a");
        env.put("AICOIN_PROXY_GOOGLE_APIKEY", "g");
        Map<String, Object> body = parse(SkillsHandler.buildJson(ProxyConfig.load(env), null));

        assertEquals(List.of("anthropic", "google"), routing(body, "text", "code"));
        assertEquals(List.of("google", "anthropic"), routing(body, "text", "science"));
        // No image or audio key is configured, so there is nothing to route there and the page
        // says so rather than listing providers that cannot be called.
        assertTrue(routing(body, "image", "creative").isEmpty());
        assertTrue(routing(body, "audio", "creative").isEmpty());
    }

    @Test
    void listsEverySubjectATaggerCanProduce() {
        Map<String, Object> body = parse(SkillsHandler.buildJson(ProxyConfig.load(new HashMap<>()), null));
        assertEquals(SubjectTagger.subjects(), body.get("subjects"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void publishesTheRatingsThemselvesIncludingProvidersWithNoKey() {
        // The ratings are what the deployment believes; whether a key happens to be configured is
        // a separate fact, published in /health. Both are needed to explain a routing decision.
        Map<String, Object> body = parse(SkillsHandler.buildJson(ProxyConfig.load(new HashMap<>()), null));
        List<Map<String, Object>> providers = (List<Map<String, Object>>) body.get("providers");
        Map<String, Object> anthropic = providers.stream()
                .filter(p -> "anthropic".equals(p.get("name"))).findFirst().orElseThrow();
        assertEquals(5, ((Map<String, Object>) anthropic.get("capabilities")).get("text"));
        assertEquals(5, ((Map<String, Object>) anthropic.get("subjects")).get("code"));
        assertFalse(((Map<String, Object>) anthropic.get("capabilities")).containsKey("image"));
    }

    @Test
    void saysWhetherEscalationIsOn() {
        Map<String, String> off = new HashMap<>();
        off.put("AICOIN_PROXY_CAPABILITIES_ESCALATION_ENABLED", "false");
        assertEquals(Boolean.FALSE, parse(SkillsHandler.buildJson(ProxyConfig.load(off), null)).get("escalation"));
        assertEquals(Boolean.TRUE,
                parse(SkillsHandler.buildJson(ProxyConfig.load(new HashMap<>()), null)).get("escalation"));
    }
}
