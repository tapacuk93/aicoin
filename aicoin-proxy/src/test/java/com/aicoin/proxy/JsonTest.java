package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The escape standing between free-form model output and both a provider's request body and this
 * proxy's own response. Model text arrives with quotes, backslashes and newlines in it as a matter
 * of course, so this is on the path of every consortium turn.
 */
class JsonTest {

    /** @return the value after a trip through {@link Json#string} and back out of a JSON parse. */
    private static String roundTrip(String value) {
        Object parsed = new Yaml().load("{\"v\":" + Json.string(value) + "}");
        return (String) ((Map<?, ?>) parsed).get("v");
    }

    @Test
    void quotesBackslashesAndNewlinesSurviveARoundTrip() {
        String awkward = "quote \" backslash \\ newline \n tab \t carriage \r end";
        assertEquals(awkward, roundTrip(awkward));
    }

    @Test
    void controlCharactersAreEscapedRatherThanEmitted() {
        // A raw control character in a JSON string is invalid JSON, and providers reject the
        // request rather than repairing it.
        assertEquals("\"a\\u0000b\"", Json.string("a\u0000b"));
        assertEquals("\"a\\u001fb\"", Json.string("a\u001fb"));
    }

    @Test
    void lineSeparatorsAreEscapedForTheBrowsersThatReadThisResponse() {
        // Legal JSON, but a raw U+2028 terminates a JavaScript string literal — and this proxy's
        // own response is read by the wallet page.
        assertEquals("\"a\\u2028b\"", Json.string("a\u2028b"));
    }

    @Test
    void nullIsTheJsonLiteralNotAQuotedWord() {
        assertEquals("null", Json.string(null));
    }
}
