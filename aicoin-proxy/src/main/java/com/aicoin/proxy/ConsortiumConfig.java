package com.aicoin.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code consortium} config block, per CONTRACT.md's "Consortium" section: whether the
 * endpoint is served at all, how many review rounds it may run, how much each turn may write, who
 * edits, and which model each panelist answers with.
 *
 * <p>Models are config rather than code for the same reason rates are: providers rename and retire
 * them on their own schedule, and a consortium that starts 502-ing because a model id lapsed
 * should be a YAML edit or an env var away from working, not a release.
 */
final class ConsortiumConfig {

    private final boolean enabled;
    private final int maxRounds;
    private final int maxOutputTokens;
    private final int maxContextChars;
    private final String editor;
    private final Map<String, String> models;

    ConsortiumConfig(boolean enabled, int maxRounds, int maxOutputTokens, int maxContextChars,
                      String editor, Map<String, String> models) {
        this.enabled = enabled;
        this.maxRounds = maxRounds;
        this.maxOutputTokens = maxOutputTokens;
        this.maxContextChars = maxContextChars;
        this.editor = editor == null ? "" : editor;
        this.models = models == null ? Map.of() : new LinkedHashMap<>(models);
    }

    /** Whether {@code POST /consortium} is served. Off means {@code 404}, as if it did not exist. */
    boolean isEnabled() {
        return enabled;
    }

    /**
     * The most review rounds one call may run before it returns whatever it has. The cap is what
     * makes "until no more comments" terminate: reviewers can always find something, and each
     * round is one paid call per panelist.
     */
    int getMaxRounds() {
        return maxRounds;
    }

    /** Output cap sent to every panelist on every turn. */
    int getMaxOutputTokens() {
        return maxOutputTokens;
    }

    /**
     * How much of the panel's shared record one turn may be given. It is sent to every panelist on
     * every turn, so its size is multiplied by the panel and by the rounds, and every character of
     * it is billed as input. Past this, the oldest rounds are dropped — see {@link SharedContext}.
     */
    int getMaxContextChars() {
        return maxContextChars;
    }

    /**
     * The panelist that merges the drafts and applies the comments. Empty means "the first
     * panelist", so a deployment that loses a provider still has an editor.
     */
    String getEditor() {
        return editor;
    }

    /** @return the model this provider answers with, or null if none is configured. */
    String modelFor(String provider) {
        return models.get(provider);
    }

    Map<String, String> getModels() {
        return models;
    }
}
