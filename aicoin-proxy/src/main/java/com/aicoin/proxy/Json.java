package com.aicoin.proxy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON in and out. {@link #load} is the one parser for every JSON body this proxy reads — client
 * requests, provider responses, signed payloads and its own Redis entries. {@link #string} is
 * the string-to-JSON escape used wherever this proxy puts text it did not author into a JSON body:
 * the consortium path, which both <em>builds</em> request bodies for providers and echoes free-form
 * model output back to a client, and {@link HealthHandler}, which reports whatever a provider or a
 * failed connection had to say for itself. Everywhere else hand-builds JSON out of numbers and
 * addresses it already controls.
 *
 * <p>Model output is exactly the kind of text that breaks naive quoting: it arrives with quotes,
 * backslashes, newlines and the occasional control character, and it is written into both an
 * upstream request body and this proxy's own response.
 */
final class Json {

    /** Nesting beyond this is refused rather than allowed to overflow the parser's stack. */
    private static final int MAX_DEPTH = 512;

    private Json() {
    }

    /**
     * Parses one JSON document into the shapes SnakeYAML used to hand back, so callers did not
     * change: objects as insertion-ordered {@link Map}s (a repeated key keeps its last value),
     * arrays as {@link List}s, strings, {@link Boolean}s, {@code null}, and integers as the
     * narrowest of {@link Integer}, {@link Long} and {@link BigInteger}, anything with a fraction
     * or exponent as a {@link Double}.
     *
     * <p>This replaced {@code Json.load(...)}, which is not a JSON parser: it rejected the
     * {@code \/} escape (Apple's JSONSerialization writes one for every "/", so any iOS prompt with
     * a slash in it got a 400), folded a raw U+0085 inside a string into a space, turned a lone
     * escaped surrogate into "?", and refused any document over 3 MiB of code points — a long
     * prompt or a base64 image response.
     *
     * @return the parsed value; {@code null} for {@code null} input or input that is only
     *         whitespace (as SnakeYAML did)
     * @throws IllegalArgumentException when {@code text} is not exactly one JSON value
     */
    static Object load(String text) {
        if (text == null) {
            return null;
        }
        Parser parser = new Parser(text);
        parser.skipBom();
        parser.skipWhitespace();
        if (parser.pos == text.length()) {
            return null;
        }
        Object value = parser.value(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.error("unexpected trailing content");
        }
        return value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at offset " + pos + ": " + message);
        }

        void skipBom() {
            if (pos < s.length() && s.charAt(pos) == '\uFEFF') {
                pos++;
            }
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth + 1);
                case '[':
                    return array(depth + 1);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private void literal(String word) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
        }

        private Map<String, Object> object(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("expected a string key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, value(depth));
                skipWhitespace();
                if (pos >= s.length()) {
                    throw error("unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(value(depth));
                skipWhitespace();
                if (pos >= s.length()) {
                    throw error("unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or ']'");
                }
            }
        }

        private void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        private String string() {
            pos++; // opening quote
            int start = pos;
            // Fast path: no escapes, so the value is a plain substring.
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '"') {
                    return s.substring(start, pos++);
                }
                if (c == '\\' || c < 0x20) {
                    break;
                }
                pos++;
            }
            StringBuilder out = new StringBuilder(pos - start + 16).append(s, start, pos);
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    pos--;
                    throw error("unescaped control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    break;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(e);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        // Each \\uXXXX is one UTF-16 unit, so an escaped surrogate pair lands as
                        // the two halves of the one character it encodes.
                        out.append(hex4());
                        break;
                    default:
                        pos--;
                        throw error("invalid escape '\\" + e + "'");
                }
            }
            throw error("unterminated string");
        }

        private char hex4() {
            if (pos + 4 > s.length()) {
                throw error("truncated \\u escape");
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                if (d < 0) {
                    throw error("invalid \\u escape");
                }
                v = (v << 4) | d;
            }
            pos += 4;
            return (char) v;
        }

        private Object number() {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            if (pos >= s.length()) {
                throw error("truncated number");
            }
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (isDigit()) {
                while (isDigit()) {
                    pos++;
                }
            } else {
                throw error("invalid number");
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                integral = false;
                pos++;
                if (!isDigit()) {
                    throw error("expected a digit after '.'");
                }
                while (isDigit()) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                integral = false;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                if (!isDigit()) {
                    throw error("expected a digit in exponent");
                }
                while (isDigit()) {
                    pos++;
                }
            }
            String literal = s.substring(start, pos);
            if (!integral) {
                return Double.parseDouble(literal);
            }
            int digits = literal.length() - (literal.charAt(0) == '-' ? 1 : 0);
            if (digits <= 9) {
                return Integer.parseInt(literal);
            }
            if (digits <= 18) {
                long v = Long.parseLong(literal);
                return v == (int) v ? (Object) (int) v : (Object) v;
            }
            BigInteger big = new BigInteger(literal);
            return big.bitLength() < 64 ? (Object) big.longValue() : big;
        }

        private boolean isDigit() {
            return pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9';
        }
    }

    /**
     * Writes back what {@link #load} produced: maps, lists, strings, numbers, booleans and null.
     * Used only where the proxy must hand a provider's document on with parts removed (see {@link
     * VoiceOwnership}); everything it does not remove goes out as it came in, save for number
     * spelling ({@code 1.50} becomes {@code 1.5}).
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append(string((String) value));
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(string(String.valueOf(e.getKey()))).append(':');
                write(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (value instanceof Double) {
            double d = (Double) value;
            out.append(Double.isFinite(d) ? Double.toString(d) : "null");
        } else {
            out.append(value);
        }
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
