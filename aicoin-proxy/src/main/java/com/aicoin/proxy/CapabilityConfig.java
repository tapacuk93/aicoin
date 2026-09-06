package com.aicoin.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code capabilities} config block behind {@code POST /text}, {@code /image} and {@code
 * /audio}: what each provider is rated at ({@link ProviderSkills}), which model answers for a
 * given capability, which voice speaks, and whether a single model may escalate its answer to the
 * whole panel.
 *
 * <p>Text models are not here. Those come from {@code consortium.models}, because they are the
 * same models: the model that answers {@code POST /text} alone is the one that would sit on the
 * panel if it escalated, and two places to configure that would eventually disagree.
 */
final class CapabilityConfig {

    private final ProviderSkills skills;
    private final Map<String, String> imageModels;
    private final Map<String, String> audioModels;
    private final Map<String, String> voices;
    private final boolean escalationEnabled;

    CapabilityConfig(ProviderSkills skills, Map<String, String> imageModels, Map<String, String> audioModels,
                      Map<String, String> voices, boolean escalationEnabled) {
        this.skills = skills;
        this.imageModels = imageModels == null ? Map.of() : new LinkedHashMap<>(imageModels);
        this.audioModels = audioModels == null ? Map.of() : new LinkedHashMap<>(audioModels);
        this.voices = voices == null ? Map.of() : new LinkedHashMap<>(voices);
        this.escalationEnabled = escalationEnabled;
    }

    ProviderSkills getSkills() {
        return skills;
    }

    /**
     * @return the model this provider answers the capability with, or "" if none is configured —
     * which, like a rating of 0, means this provider is not a candidate for that capability.
     */
    String modelFor(Capability capability, String provider) {
        switch (capability) {
            case IMAGE:
                return imageModels.getOrDefault(provider, "");
            case AUDIO:
                return audioModels.getOrDefault(provider, "");
            default:
                return "";
        }
    }

    /** @return the provider's own voice identifier for {@code POST /audio}: an id, or a name. */
    String voiceFor(String provider) {
        return voices.getOrDefault(provider, "");
    }

    /**
     * Whether a single model may spend a caller's coins on a panel by declaring the request
     * complex. Off means {@code POST /text} is always exactly one call — which is what a deployment
     * that wants a predictable per-request price wants.
     */
    boolean isEscalationEnabled() {
        return escalationEnabled;
    }
}
