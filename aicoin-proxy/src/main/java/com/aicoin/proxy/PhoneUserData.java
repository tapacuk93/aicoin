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
 * The user data a hosted phone boots with: {@code src/main/resources/anyphone/user-data.sh} (or the
 * file {@code anyphone.userDataTemplate} names) with its {@code {{NAME}}} placeholders filled in.
 * The template is the one place the script lives; this class only substitutes.
 */
final class PhoneUserData {

    static final String BUNDLED = "/anyphone/user-data.sh";

    /** Every value goes into a shell heredoc and a URL, so it is held to characters that are inert in both. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._/@:+=-]{1,200}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

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

    static Map<String, String> values(AnyphoneConfig config, String phoneId, String token) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ANYPHONE_TOKEN", token);
        values.put("ANYPHONE_PHONE_ID", phoneId);
        values.put("ANYPHONE_VERSION", config.getVersion());
        values.put("ANYPHONE_REPO", config.getRepo());
        values.put("AWS_REGION", config.getRegion());
        return values;
    }

    /** Fills every placeholder. An unknown placeholder or an unsafe value is an error, never a silent blank. */
    static String render(String template, Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null || !SAFE.matcher(e.getValue()).matches()) {
                throw new IllegalArgumentException(e.getKey() + " is not shell-safe");
            }
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("user-data template has an unknown placeholder {{" + m.group(1) + "}}");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
