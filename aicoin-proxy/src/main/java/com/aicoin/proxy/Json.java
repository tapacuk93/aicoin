package com.aicoin.proxy;

/**
 * The one string-to-JSON escape used by the consortium path, which is the only place in this proxy
 * that both <em>builds</em> request bodies for providers and echoes free-form model output back to
 * a client. Everywhere else hand-builds JSON out of numbers and addresses it already controls.
 *
 * <p>Model output is exactly the kind of text that breaks naive quoting: it arrives with quotes,
 * backslashes, newlines and the occasional control character, and it is written into both an
 * upstream request body and this proxy's own response.
 */
final class Json {

    private Json() {
    }

    /** @return {@code value} as a quoted, escaped JSON string — or {@code null} (unquoted) when it is null. */
    static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    // Everything below 0x20 must be escaped to stay valid JSON; U+2028/U+2029 are
                    // legal JSON but break JavaScript string literals, and this response is read
                    // by browsers.
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }
}
