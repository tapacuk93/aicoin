import Foundation

/// One AI provider the aicoin-proxy understands, per CONTRACT.md's `providers.*` config block and
/// its `X-AI` header vocabulary (`openai|anthropic|google|mistral|cohere|elevenlabs|stability|kimi`,
/// case-insensitive on the wire; this package always sends the canonical lowercase `rawValue`).
public enum AIProviderRoute: String, CaseIterable, Sendable {
    case anthropic
    case openai
    case google
    case mistral
    case cohere
    case elevenlabs
    case stability
    case kimi

    public var displayName: String {
        switch self {
        case .anthropic: return "Anthropic (Claude)"
        case .openai: return "OpenAI"
        case .google: return "Google Gemini"
        case .mistral: return "Mistral"
        case .cohere: return "Cohere"
        case .elevenlabs: return "ElevenLabs"
        case .stability: return "Stability AI"
        case .kimi: return "Kimi (Moonshot)"
        }
    }
}

/// Decorates an `HTTPTransport` so that requests to a known AI-provider host are transparently
/// re-routed through the aicoin-proxy instead of the provider's real domain — same method, same
/// path (including query string), same body — carrying the two headers the proxy's generic
/// `X-AI`-routed path needs: `X-AI` (which provider) and `X-Api-Key` (an **API token**, per
/// CONTRACT.md's "Auth for AI-proxy calls" — never a bare address; the generic path only accepts
/// tokens).
///
/// This generalizes All Languages Learner's `AICoinGateway` and Learn It's `AIcoinWalletRouter`
/// into one host-routing table (now covering every provider CONTRACT.md's proxy config lists,
/// including `cohere`/`stability`, which neither existing app's table had), but makes one
/// deliberate behavior change from both of them: **there is no bring-your-own-key fallback**. Per
/// this task's brief, apps built on this package no longer carry a personal provider key to fall
/// back to at all, so a `402` (insufficient balance) is thrown as a typed `AICoinError
/// .insufficientBalance`, not silently retried against `request` unmodified. App UI is expected to
/// catch that error and present `BuyAICoinSheet`. The same event is also broadcast on `eventBus`
/// so a balance badge elsewhere on screen can react without every call site plumbing it through.
///
/// A request to a host this table doesn't recognize (or one sent when no token is configured
/// system-wide, before wallet setup) can't be a "fallback" concern at all under this new design
/// since apps hold no personal key — see `tokenProvider`'s doc comment for what happens when it
/// returns `nil`.
public struct AICoinRouter: HTTPTransport {
    public static let knownHosts: [String: AIProviderRoute] = [
        "api.anthropic.com": .anthropic,
        "api.openai.com": .openai,
        "generativelanguage.googleapis.com": .google,
        "api.mistral.ai": .mistral,
        "api.cohere.ai": .cohere,
        "api.elevenlabs.io": .elevenlabs,
        "api.stability.ai": .stability,
        // Kimi's API host, unchanged by the platform docs' move to kimi.ai.
        "api.moonshot.ai": .kimi,
    ]

    private let underlying: any HTTPTransport
    private let baseURL: URL
    private let eventBus: AICoinEventBus
    private let tokenProvider: @Sendable () -> String?

    /// - Parameters:
    ///   - underlying: The transport to use for the actual network call (and for any host this
    ///     router doesn't recognize as an AI provider — those pass straight through, untouched).
    ///   - baseURL: The aicoin-proxy's own host. Defaults to `AICoinConfig.defaultBaseURL`.
    ///   - eventBus: Where `.paidCallSucceeded`/`.insufficientBalance` are published. Defaults to
    ///     the process-wide `.shared` bus; pass a private instance in tests.
    ///   - tokenProvider: Called fresh on every request to a known provider host to obtain the
    ///     current API token (see `WalletSigner.issueAPIToken`). Returning `nil`/empty means "no
    ///     wallet is set up for AI calls yet" — the router throws `AICoinError.missingToken`
    ///     rather than guessing at a fallback, since this package's apps hold no personal key to
    ///     fall back to.
    public init(
        underlying: any HTTPTransport,
        baseURL: URL = AICoinConfig.defaultBaseURL,
        eventBus: AICoinEventBus = .shared,
        tokenProvider: @escaping @Sendable () -> String?
    ) {
        self.underlying = underlying
        self.baseURL = baseURL
        self.eventBus = eventBus
        self.tokenProvider = tokenProvider
    }

    public func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        guard let host = request.url?.host, let provider = Self.knownHosts[host] else {
            return try await underlying.data(for: request)
        }
        guard let token = tokenProvider(), !token.isEmpty else {
            throw AICoinError.missingToken
        }
        guard let proxied = Self.rewritten(request, provider: provider, baseURL: baseURL, token: token) else {
            return try await underlying.data(for: request)
        }

        let (data, response) = try await underlying.data(for: proxied)
        guard let http = response as? HTTPURLResponse else { return (data, response) }

        if http.statusCode == 402 {
            let balance = Self.parseBalance(from: data)
            eventBus.events.send(.insufficientBalance(balance: balance))
            throw AICoinError.insufficientBalance(balance: balance)
        }
        if (200..<300).contains(http.statusCode) {
            eventBus.events.send(.paidCallSucceeded)
        }
        return (data, response)
    }

    /// Same method/path/query/body as `request`, re-hosted at `baseURL` with the proxy's two
    /// headers added. Everything else about `request` (including the provider's own auth header,
    /// which the proxy discards/replaces regardless) is left completely untouched.
    static func rewritten(_ request: URLRequest, provider: AIProviderRoute, baseURL: URL, token: String) -> URLRequest? {
        guard
            let url = request.url,
            var components = URLComponents(url: url, resolvingAgainstBaseURL: false),
            let baseComponents = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        else { return nil }

        components.scheme = baseComponents.scheme
        components.host = baseComponents.host
        components.port = baseComponents.port
        guard let newURL = components.url else { return nil }

        var newRequest = request
        newRequest.url = newURL
        newRequest.setValue(provider.rawValue, forHTTPHeaderField: "X-AI")
        newRequest.setValue(token, forHTTPHeaderField: "X-Api-Key")
        return newRequest
    }

    /// CONTRACT.md's documented 402 body is exactly `{"error":"insufficient aicoin
    /// balance","balance":<value>}` — best-effort parse, `nil` if the body doesn't match (still
    /// treated as insufficient balance either way, since 402 from this proxy has no other meaning).
    static func parseBalance(from data: Data) -> Double? {
        struct Body: Decodable { let balance: Double? }
        return (try? JSONDecoder().decode(Body.self, from: data))?.balance
    }
}
