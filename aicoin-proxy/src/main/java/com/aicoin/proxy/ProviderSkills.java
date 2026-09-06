package com.aicoin.proxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each provider is rated at: 0 to 5 per capability, and 0 to 5 per {@link SubjectTagger
 * subject} where a provider is notably better or worse at one than at its work in general.
 *
 * <p><b>These are the operator's opinion, not a measurement.</b> Nothing here is benchmarked and
 * nothing here is learned from traffic; the defaults are one person's reading of which model to
 * reach for, and they will be wrong for somebody's workload the day they are written. That is why
 * they live in config rather than in code: a deployment that disagrees edits the numbers, and a
 * caller who disagrees names a provider explicitly and is never routed at all.
 *
 * <p>A capability rating of <b>0 means never route</b>, which is how a provider that cannot do a
 * thing at all is expressed — ElevenLabs writes no prose, and Anthropic returns no images. Between
 * providers that can, the subject rating decides, and a provider with no rating for that subject
 * falls back to its capability rating: no entry means no opinion, not a bad score.
 *
 * <p>A bare subject key ({@code code}) rates the provider's <em>text</em>; a media capability takes
 * a prefixed one ({@code image-creative}, {@code audio-creative}). Being good at code says nothing
 * about who should read a sentence about code out loud, and before the prefix existed it decided
 * exactly that — {@code /audio} on a code-tagged request ranked OpenAI over ElevenLabs on the
 * strength of OpenAI's rating for writing code.
 */
final class ProviderSkills {

    static final int MAX_RATING = 5;

    private final Map<String, Map<String, Integer>> capabilityRatings;
    private final Map<String, Map<String, Integer>> subjectRatings;

    ProviderSkills(Map<String, Map<String, Integer>> capabilityRatings,
                    Map<String, Map<String, Integer>> subjectRatings) {
        this.capabilityRatings = deepCopy(capabilityRatings);
        this.subjectRatings = deepCopy(subjectRatings);
    }

    private static Map<String, Map<String, Integer>> deepCopy(Map<String, Map<String, Integer>> source) {
        Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Map<String, Integer>> entry : source.entrySet()) {
                copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
            }
        }
        return copy;
    }

    /** @return this provider's rating for the capability itself: 0 (never route) to 5. */
    int capabilityRating(String provider, Capability capability) {
        Map<String, Integer> ratings = capabilityRatings.get(provider);
        if (ratings == null) {
            return 0;
        }
        Integer rating = ratings.get(capability.path());
        return rating == null ? 0 : clamp(rating);
    }

    /**
     * @return what this provider scores for this kind of work: its rating for the subject if it has
     * one, otherwise its rating for the capability. Zero — never route — whenever it cannot do the
     * capability at all, whatever the subject says.
     */
    int score(String provider, Capability capability, String subject) {
        int base = capabilityRating(provider, capability);
        if (base == 0) {
            return 0;
        }
        Integer rating = subjectRating(provider, capability, subject);
        return rating == null ? base : clamp(rating);
    }

    /** @return the rating for this subject <em>under this capability</em>, or null if there is none. */
    private Integer subjectRating(String provider, Capability capability, String subject) {
        Map<String, Integer> subjects = subjectRatings.get(provider);
        if (subjects == null || subject == null) {
            return null;
        }
        Integer scoped = subjects.get(key(capability, subject));
        if (scoped != null) {
            return scoped;
        }
        // A bare key is a rating for text, and applies to nothing else.
        return capability == Capability.TEXT ? subjects.get(subject) : null;
    }

    /** The config key a subject rating is written under for this capability. */
    static String key(Capability capability, String subject) {
        // Hyphen, not a dot: config paths are dotted, and "image.creative" under a provider would
        // have to be a nested map — which collides with `image` already being that provider's
        // capability rating.
        return capability == Capability.TEXT ? subject : capability.path() + "-" + subject;
    }

    /** @return true when this provider has an explicit rating for the subject, rather than a fallback. */
    boolean hasSubjectRating(String provider, Capability capability, String subject) {
        return subjectRating(provider, capability, subject) != null;
    }

    /**
     * The providers that can do this capability at all, best first.
     *
     * <p>At the same score, a provider rated for <em>this subject</em> outranks one that got there
     * on its general capability rating: an explicit opinion about the subject is worth more than
     * the same number arrived at by having no opinion. Past that, ties keep {@link
     * ProxyConfig#PROVIDER_NAMES} order, so a given request routes the same way every time it is
     * asked — a router that shuffles equally-rated providers makes a failure impossible to
     * reproduce.
     *
     * @param available names a provider this deployment can actually call right now: configured,
     *                  and not known to be down. Rating a provider highly is not the same as it
     *                  answering.
     */
    List<String> rank(Capability capability, String subject, java.util.function.Predicate<String> available) {
        List<String> candidates = new ArrayList<>();
        for (String provider : ProxyConfig.PROVIDER_NAMES) {
            if (score(provider, capability, subject) > 0 && (available == null || available.test(provider))) {
                candidates.add(provider);
            }
        }
        candidates.sort(ranking(capability, subject, ProxyConfig.PROVIDER_NAMES));
        return candidates;
    }

    /**
     * The order this table puts providers in: best score first, an explicit subject rating ahead of
     * the same score reached by fallback, then {@code canonicalOrder} so equals never swap places.
     *
     * <p>Shared with the consortium, which ranks its panel the same way for the same reason — the
     * first panelist is the editor. One comparator rather than two, so the two cannot drift.
     */
    Comparator<String> ranking(Capability capability, String subject, List<String> canonicalOrder) {
        return Comparator
                .comparingInt((String provider) -> score(provider, capability, subject))
                .thenComparingInt(provider -> hasSubjectRating(provider, capability, subject) ? 1 : 0)
                .reversed()
                .thenComparingInt(canonicalOrder::indexOf);
    }

    private static int clamp(int rating) {
        return Math.max(0, Math.min(MAX_RATING, rating));
    }

    /**
     * The shipped ratings. Read them as "which of these would you reach for", not as a benchmark.
     *
     * <p>Text is rated for every chat provider {@link ChatAdapter} knows the shape of. Image is
     * OpenAI and Stability; audio is ElevenLabs and OpenAI. Everything unlisted is 0, which is why
     * asking {@code /image} never reaches Anthropic.
     */
    static ProviderSkills defaults() {
        Map<String, Map<String, Integer>> capabilities = new LinkedHashMap<>();
        capabilities.put("anthropic", ratings("text", 5));
        capabilities.put("openai", ratings("text", 5, "image", 5, "audio", 4));
        capabilities.put("google", ratings("text", 4));
        capabilities.put("mistral", ratings("text", 3));
        capabilities.put("kimi", ratings("text", 4));
        capabilities.put("elevenlabs", ratings("audio", 5));
        capabilities.put("stability", ratings("image", 4));

        Map<String, Map<String, Integer>> subjects = new LinkedHashMap<>();
        subjects.put("anthropic", ratings("code", 5, "writing", 5, "analysis", 5, "legal", 4, "creative", 4));
        subjects.put("openai", ratings("code", 5, "math", 5, "science", 4, "analysis", 4, "creative", 4));
        subjects.put("google", ratings("science", 5, "translation", 5, "math", 4, "analysis", 4, "code", 3));
        subjects.put("mistral", ratings("translation", 4, "code", 3, "writing", 3));
        subjects.put("kimi", ratings("translation", 5, "analysis", 4, "code", 4, "writing", 4));
        // For a media provider the subject barely moves the choice — there are two candidates — but
        // an illustration is what Stability is for, and a spoken narration is what ElevenLabs is
        // for. Prefixed, because these are ratings for drawing and speaking, not for writing.
        subjects.put("stability", ratings("image-creative", 5));
        subjects.put("elevenlabs", ratings("audio-creative", 5));
        return new ProviderSkills(capabilities, subjects);
    }

    /** {@code ratings("text", 5, "image", 3)} — pairs, for readability at the call sites above. */
    private static Map<String, Integer> ratings(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    /** The whole table, for {@code GET /skills} and for tests. */
    Map<String, Map<String, Integer>> capabilities() {
        return deepCopy(capabilityRatings);
    }

    Map<String, Map<String, Integer>> subjects() {
        return deepCopy(subjectRatings);
    }
}
