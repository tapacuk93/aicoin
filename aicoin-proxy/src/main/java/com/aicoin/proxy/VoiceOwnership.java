package com.aicoin.proxy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ElevenLabs voices, made per wallet on an account every wallet shares.
 *
 * <p>Infinite AI Radio designs a voice for each new station and All Languages Learner one for each
 * card ({@code POST /v1/text-to-voice[/create-voice-from-preview]}), both use it by id, list voices
 * to choose from ({@code GET /v1/voices}), and delete their old ones ({@code DELETE
 * /v1/voices/<id>}) to stay inside the account's voice slots. That is a stored-object API, and
 * every one of those voices lives in the proxy's single ElevenLabs account. Forwarded as-is, any
 * wallet could list every other wallet's voices — named after the station topic or the card they
 * were made for, with a description written from its content — and delete them.
 *
 * <p>So the proxy remembers which wallet created which voice ({@link AicoinLedger#addVoiceOwner}),
 * and:
 * <ul>
 *   <li>a voice listing shows ElevenLabs' stock ({@code premade}) voices plus the caller's own, and
 *       nothing else;</li>
 *   <li>a voice can be deleted by the wallet that created it. A voice with no recorded owner —
 *       one made before this existed — can still be deleted by whoever names it, because the apps
 *       that made those voices still hold their ids and must be able to free the slots; since no
 *       listing reveals such an id any more, only the device that made it knows it.</li>
 * </ul>
 * Speaking with a voice is not restricted: it needs the id, and the id is only ever given to the
 * wallet that created the voice.
 */
final class VoiceOwnership {

    private VoiceOwnership() {
    }

    /** @return true for the calls whose answer names a voice just created for the caller. */
    static boolean createsVoice(String provider, String method, String path) {
        return "elevenlabs".equals(provider) && "POST".equalsIgnoreCase(method)
                && ("/v1/text-to-voice".equals(path) || "/v1/text-to-voice/create-voice-from-preview".equals(path));
    }

    /** @return true for the voice listing, whose answer is narrowed to what the caller may see. */
    static boolean listsVoices(String provider, String method, String path) {
        return "elevenlabs".equals(provider) && "GET".equalsIgnoreCase(method) && "/v1/voices".equals(path);
    }

    /** @return the voice id a {@code DELETE /v1/voices/<id>} names, or empty for any other call. */
    static Optional<String> deletedVoice(String provider, String method, String path) {
        String prefix = "/v1/voices/";
        if (!"elevenlabs".equals(provider) || !"DELETE".equalsIgnoreCase(method) || !path.startsWith(prefix)) {
            return Optional.empty();
        }
        String id = path.substring(prefix.length());
        return id.isEmpty() || id.contains("/") ? Optional.empty() : Optional.of(id);
    }

    /** @return the {@code voice_id} a voice-creation answer carries, if it carries one. */
    static Optional<String> createdVoiceId(byte[] body) {
        try {
            Object parsed = Json.load(new String(body, StandardCharsets.UTF_8));
            if (parsed instanceof Map) {
                Object id = ((Map<?, ?>) parsed).get("voice_id");
                if (id instanceof String && !((String) id).isEmpty()) {
                    return Optional.of((String) id);
                }
            }
        } catch (IllegalArgumentException e) {
            // Not JSON: nothing to record, and the caller gets the answer unchanged.
        }
        return Optional.empty();
    }

    /**
     * Narrows a {@code GET /v1/voices} answer to the stock voices plus {@code owned}. An answer that
     * cannot be read is replaced by an empty listing rather than passed on, since passing it on is
     * exactly the disclosure this exists to prevent.
     */
    static byte[] filterListing(byte[] body, Set<String> owned) {
        Object parsed;
        try {
            parsed = Json.load(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            parsed = null;
        }
        Map<Object, Object> out = new LinkedHashMap<>();
        List<Object> kept = new ArrayList<>();
        if (parsed instanceof Map) {
            out.putAll((Map<?, ?>) parsed);
            Object voices = out.get("voices");
            if (voices instanceof List) {
                for (Object v : (List<?>) voices) {
                    if (v instanceof Map && visible((Map<?, ?>) v, owned)) {
                        kept.add(v);
                    }
                }
            }
        }
        out.put("voices", kept);
        return Json.write(out).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean visible(Map<?, ?> voice, Set<String> owned) {
        if ("premade".equals(voice.get("category"))) {
            return true;
        }
        Object id = voice.get("voice_id");
        return id instanceof String && owned.contains(id);
    }
}
