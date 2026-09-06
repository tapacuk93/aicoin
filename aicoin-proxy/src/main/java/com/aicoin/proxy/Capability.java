package com.aicoin.proxy;

import java.util.Locale;
import java.util.Optional;

/**
 * What a caller wants done, rather than who they want to do it — the axis the consolidated
 * endpoints are organised on: {@code POST /text}, {@code POST /image}, {@code POST /audio}.
 *
 * <p>The rest of this proxy is deliberately provider-shaped: the client sends OpenAI's own request
 * to OpenAI's own path with {@code X-AI: openai}, and the proxy only injects a key. That is the
 * right default for a client that has already chosen a provider and written to its API. It is the
 * wrong shape for a client that just wants a paragraph written, and it forces two decisions on
 * everybody — which provider, and what that provider's request body looks like this month — that
 * most callers have no view on.
 *
 * <p>So these endpoints take the request in one shape, pick the provider by {@link ProviderSkills
 * what each is rated at} for the {@link SubjectTagger tagged subject}, and hand back one shape.
 * The provider that answered is always named in the response: routing chosen for you is only
 * acceptable if you can see what was chosen.
 */
enum Capability {

    /** Chat/completion. The one capability where a model can escalate its own answer to a panel. */
    TEXT("text"),
    /** Text to image. */
    IMAGE("image"),
    /** Text to speech. */
    AUDIO("audio");

    private final String path;

    Capability(String path) {
        this.path = path;
    }

    /** The endpoint path segment, e.g. {@code text} for {@code POST /text}. */
    String path() {
        return path;
    }

    /** Resolves a request path ({@code /text}) to a capability, or empty if it names none. */
    static Optional<Capability> fromPath(String requestPath) {
        if (requestPath == null || requestPath.length() < 2 || requestPath.charAt(0) != '/') {
            return Optional.empty();
        }
        String name = requestPath.substring(1).toLowerCase(Locale.ROOT);
        for (Capability capability : values()) {
            if (capability.path.equals(name)) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }
}
