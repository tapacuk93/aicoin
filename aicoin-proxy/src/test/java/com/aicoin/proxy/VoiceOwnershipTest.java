package com.aicoin.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** ElevenLabs voices are made per wallet on one shared account; each wallet sees and deletes only its own. */
class VoiceOwnershipTest {

    private static final String LISTING = "{\"voices\":["
            + "{\"voice_id\":\"21m00Tcm4TlvDq8ikWAM\",\"name\":\"Rachel\",\"category\":\"premade\"},"
            + "{\"voice_id\":\"mine1\",\"name\":\"Auto: my station\",\"category\":\"generated\"},"
            + "{\"voice_id\":\"theirs1\",\"name\":\"Auto: somebody's private topic\",\"category\":\"generated\"},"
            + "{\"voice_id\":\"legacy1\",\"name\":\"Custom Voice 3\",\"category\":\"cloned\"}"
            + "],\"has_more\":false}";

    @SuppressWarnings("unchecked")
    private static List<String> ids(byte[] body) {
        Map<String, Object> m = (Map<String, Object>) Json.load(new String(body, StandardCharsets.UTF_8));
        return ((List<Map<String, Object>>) m.get("voices")).stream().map(v -> (String) v.get("voice_id")).toList();
    }

    @Test
    void aListingShowsStockVoicesAndTheCallersOwnOnly() {
        byte[] out = VoiceOwnership.filterListing(LISTING.getBytes(StandardCharsets.UTF_8), Set.of("mine1"));
        assertEquals(List.of("21m00Tcm4TlvDq8ikWAM", "mine1"), ids(out));
        String text = new String(out, StandardCharsets.UTF_8);
        assertFalse(text.contains("somebody's private topic"));
        assertTrue(text.contains("\"has_more\":false"), "the rest of the document survives");
    }

    @Test
    void aWalletWithNoVoicesSeesOnlyStockVoices() {
        assertEquals(List.of("21m00Tcm4TlvDq8ikWAM"),
                ids(VoiceOwnership.filterListing(LISTING.getBytes(StandardCharsets.UTF_8), Set.of())));
    }

    @Test
    void anUnreadableListingIsReplacedNotPassedOn() {
        assertEquals(List.of(), ids(VoiceOwnership.filterListing("not json".getBytes(StandardCharsets.UTF_8), Set.of())));
        assertEquals(List.of(), ids(VoiceOwnership.filterListing("[1,2]".getBytes(StandardCharsets.UTF_8), Set.of())));
    }

    @Test
    void theCallsThatNeedOwnershipAreRecognised() {
        assertTrue(VoiceOwnership.createsVoice("elevenlabs", "POST", "/v1/text-to-voice"));
        assertTrue(VoiceOwnership.createsVoice("elevenlabs", "POST", "/v1/text-to-voice/create-voice-from-preview"));
        assertFalse(VoiceOwnership.createsVoice("elevenlabs", "POST", "/v1/text-to-voice/create-previews"));
        assertFalse(VoiceOwnership.createsVoice("openai", "POST", "/v1/text-to-voice"));
        assertTrue(VoiceOwnership.listsVoices("elevenlabs", "GET", "/v1/voices"));
        assertFalse(VoiceOwnership.listsVoices("elevenlabs", "GET", "/v1/models"));
        assertEquals(Optional.of("abc"), VoiceOwnership.deletedVoice("elevenlabs", "DELETE", "/v1/voices/abc"));
        assertEquals(Optional.empty(), VoiceOwnership.deletedVoice("elevenlabs", "GET", "/v1/voices/abc"));
        assertEquals(Optional.empty(), VoiceOwnership.deletedVoice("elevenlabs", "DELETE", "/v1/voices/"));
        assertEquals(Optional.empty(), VoiceOwnership.deletedVoice("elevenlabs", "DELETE", "/v1/voices/a/b"));
    }

    @Test
    void theNewVoiceIdIsReadFromTheAnswer() {
        assertEquals(Optional.of("v123"), VoiceOwnership.createdVoiceId(
                "{\"voice_id\":\"v123\",\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), VoiceOwnership.createdVoiceId("{}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), VoiceOwnership.createdVoiceId(new byte[] {(byte) 0xff}));
    }

    @Test
    void jsonWriteRoundTrips() {
        String doc = "{\"a\":[1,2.5,true,null,\"q\\\"\\n\"],\"b\":{\"c\":-3}}";
        assertEquals(Json.load(doc), Json.load(Json.write(Json.load(doc))));
    }
}
