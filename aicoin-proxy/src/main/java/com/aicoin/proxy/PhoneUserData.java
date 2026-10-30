package com.aicoin.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The user data a hosted phone boots with: anyphone's own {@code infra/aws/hosted-user-data.sh},
 * bundled verbatim as {@code src/main/resources/anyphone/hosted-user-data.sh} (or the file
 * {@code anyphone.userDataTemplate} names — point it at a newer copy without a rebuild).
 *
 * <p>That script's contract (anyphone's infra/aws/README.md, "Hosted phones"): the proxy replaces
 * {@code __TOKEN__}, {@code __PHONE_ID__}, {@code __VERSION__}, {@code __REGION__} and
 * {@code __RESTORE__} in the {@code stack.env} block; every value one line, no quotes or spaces.
 * {@code ANYPHONE_REPO} is not a placeholder there — the script carries a default release URL —
 * so its line is rewritten to {@code anyphone.releaseUrl}, which is required: the default bucket
 * no longer exists.
 */
final class PhoneUserData {

    static final String BUNDLED = "/anyphone/hosted-user-data.sh";

    /** Inert in a quoted heredoc, a sourced env file and a URL: no quotes, spaces, $ or backticks. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/:+=-]{0,200}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{32,200}");
    private static final Pattern URL = Pattern.compile("https://[A-Za-z0-9._/:-]{1,300}");
    private static final Pattern LEFTOVER = Pattern.compile("__[A-Z][A-Z_]*__");
    private static final Pattern REPO_LINE = Pattern.compile("(?m)^ANYPHONE_REPO=.*$");

    private PhoneUserData() {
    }

    static String template(AnyphoneConfig config) {
        try {
            if (!config.getUserDataTemplate().isEmpty()) {
                return Files.readString(Path.of(config.getUserDataTemplate()), StandardCharsets.UTF_8);
            }
            try (InputStream in = PhoneUserData.class.getResourceAsStream(BUNDLED)) {
                if (in == null) {
                    throw new IllegalStateException("bundled " + BUNDLED + " is missing");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not read the user-data template: " + e.getMessage(), e);
        }
    }

    /** The placeholder values for one launch. {@code restore}: the app will upload a saved state right away. */
    static Map<String, String> values(AnyphoneConfig config, String phoneId, String token, boolean restore) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("__TOKEN__", token);
        values.put("__PHONE_ID__", phoneId);
        values.put("__VERSION__", config.getVersion());
        values.put("__REGION__", config.getRegion());
        values.put("__RESTORE__", restore ? "expected" : "");
        return values;
    }

    /**
     * Fills every placeholder and sets the release URL. An unsafe value, a leftover placeholder or
     * a template without an {@code ANYPHONE_REPO=} line is an error, never a silently broken phone.
     */
    static String render(String template, Map<String, String> values, String releaseUrl) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue();
            if (v == null || !SAFE.matcher(v).matches()) {
                throw new IllegalArgumentException(e.getKey() + " is not a single shell-safe line");
            }
        }
        String token = values.get("__TOKEN__");
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("__TOKEN__ must be at least 32 base64url characters");
        }
        if (releaseUrl == null || !URL.matcher(releaseUrl).matches()) {
            throw new IllegalArgumentException("anyphone.releaseUrl must be an https URL");
        }
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        Matcher repo = REPO_LINE.matcher(out);
        if (!repo.find()) {
            throw new IllegalArgumentException("user-data template has no ANYPHONE_REPO= line");
        }
        out = repo.replaceAll(Matcher.quoteReplacement("ANYPHONE_REPO=" + releaseUrl));
        Matcher leftover = LEFTOVER.matcher(out);
        if (leftover.find()) {
            throw new IllegalArgumentException("user-data template has an unknown placeholder " + leftover.group());
        }
        return out;
    }
}
