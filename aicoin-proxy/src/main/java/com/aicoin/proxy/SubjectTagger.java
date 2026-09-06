package com.aicoin.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a request is <em>about</em>, in one word from a small fixed taxonomy, so {@link
 * ProviderSkills} can rank providers by what each is rated at for that kind of work.
 *
 * <p>This is a keyword tagger, and it is deliberately not a model call. Asking a model what a
 * request is about, before answering the request, doubles the cost of every short question and
 * adds a provider round-trip to the latency of all of them — to decide something that only
 * <em>orders</em> a list. A wrong tag costs a caller nothing but a differently-ranked provider,
 * every one of which can answer the request; a wrong tag never refuses, never blocks and never
 * fails a call. That asymmetry is what makes cheap and approximate the right trade here.
 *
 * <p>The request decides whenever it matches anything at all; the caller's context is consulted
 * only when the request matched nothing. Background is what a question is set in, but the question
 * is what is being asked — a licensing question asked about a codebase is a licensing question,
 * and any scheme that lets a pasted directory outvote the sentence with the question mark in it
 * tags every request in a session the same way. Only the head of the context is read even then.
 */
final class SubjectTagger {

    /** The tag for a request that matched nothing in particular. Every provider is rated for it. */
    static final String GENERAL = "general";

    /** How much of the caller's context is read. Enough to colour the tag, not enough to own it. */
    private static final int CONTEXT_SAMPLE_CHARS = 2_000;

    /**
     * Subject to the words that suggest it. Kept small and concrete on purpose: a taxonomy with
     * forty entries would need forty ratings per provider, which nobody can fill in honestly.
     *
     * <p><b>Ordered most specific first</b>, because ties are broken by this order and the more
     * specific reading is the better guess: "write a short story" matches both {@code creative}
     * and {@code writing}, and it is a story. {@code writing} sits last for that reason — it is
     * the one whose signals turn up inside requests that are really about something else.
     */
    private static final Map<String, List<String>> SIGNALS = new LinkedHashMap<>();

    static {
        SIGNALS.put("code", List.of("code", "coding", "function", "class ", "compile", "bug", "stack trace",
                "refactor", "api", "sql", "regex", "java", "python", "javascript", "typescript", "rust", "golang",
                "docker", "kubernetes", "git ", "unit test", "exception", "null pointer", "segfault", "npm",
                "gradle", "terminal", "shell script", "algorithm", "repository", "pull request"));
        SIGNALS.put("math", List.of("calculate", "equation", "integral", "derivative", "theorem", "proof",
                "probability", "matrix", "algebra", "geometry", "statistic", "solve for", "sum of", "logarithm",
                "prime number", "arithmetic", "percentage"));
        SIGNALS.put("translation", List.of("translate", "translation", "in spanish", "in french", "in german",
                "in japanese", "in chinese", "in ukrainian", "in russian", "into english", "localize", "localise"));
        SIGNALS.put("legal", List.of("contract", "clause", "liability", "gdpr", "license", "licence", "trademark",
                "copyright", "terms of service", "privacy policy", "compliance", "regulation", "statute",
                "jurisdiction", "indemnity"));
        SIGNALS.put("science", List.of("physics", "chemistry", "biology", "molecule", "protein", "genome",
                "clinical", "diagnosis", "experiment", "hypothesis", "quantum", "astronomy", "geology",
                "neuroscience", "medicine", "dosage"));
        SIGNALS.put("creative", List.of("story", "poem", "lyrics", "screenplay", "character", "plot", "fiction",
                "novel", "joke", "imagine a", "illustration", "artwork", "painting", "concept art", "logo",
                "album cover", "narration", "voiceover", "voice over"));
        SIGNALS.put("analysis", List.of("analyze", "analyse", "compare", "trade-off", "tradeoff", "pros and cons",
                "evaluate", "assess", "should we", "which is better", "recommend", "strategy", "decide",
                "risk", "root cause", "review this", "critique"));
        SIGNALS.put("writing", List.of("write a", "draft a", "rewrite", "edit this", "proofread", "essay",
                "blog post", "email", "headline", "copy for", "tone", "grammar", "paraphrase", "summarize",
                "summarise", "shorten", "press release", "documentation"));
    }

    private SubjectTagger() {
    }

    /** Every tag this can return, {@link #GENERAL} last. Config and docs enumerate the same list. */
    static List<String> subjects() {
        return List.of("code", "math", "translation", "writing", "creative", "analysis", "science", "legal",
                GENERAL);
    }

    static boolean isKnown(String subject) {
        return subject != null && subjects().contains(subject.toLowerCase(Locale.ROOT));
    }

    /**
     * @param prompt  the request itself
     * @param context the caller's background, or null
     * @return the best-matching subject, or {@link #GENERAL} when nothing matched. Ties are broken
     *         by the taxonomy's own order, so the same request always tags the same way.
     */
    static String tag(String prompt, String context) {
        String request = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        String best = bestMatch(request);
        if (best != null) {
            return best;
        }
        String background = context == null ? ""
                : context.substring(0, Math.min(context.length(), CONTEXT_SAMPLE_CHARS)).toLowerCase(Locale.ROOT);
        best = bestMatch(background);
        return best == null ? GENERAL : best;
    }

    /** @return the subject with the most signals in this text, or null when it matched none. */
    private static String bestMatch(String text) {
        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> entry : SIGNALS.entrySet()) {
            int score = 0;
            for (String signal : entry.getValue()) {
                if (text.contains(signal)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getKey();
            }
        }
        return best;
    }
}
