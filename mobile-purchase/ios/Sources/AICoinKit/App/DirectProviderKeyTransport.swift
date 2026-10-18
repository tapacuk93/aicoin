import Foundation

/// Talks to the AI providers directly with keys taken from the environment, so a long soak can be
/// run without a wallet and without spending anyone's coins.
///
/// It exists because the questions worth asking over a long run — does a language follow itself,
/// does a picture come back, does a chapter repeat — need hundreds of real generations to answer,
/// and the simulator has no wallet to pay for them: StoreKit purchases are not real there, so a
/// simulator balance is always zero.
///
/// Public because the package can't see an app's build configuration, but **apps must gate its use
/// behind `#if DEBUG`** (pass it to `AICoinAppRuntime(directTransport:)` only there), so nothing
/// here can reach a shipped build or a real user. Inert in any case unless the environment carries
/// a key.
///
/// The keys are read from the environment rather than launch arguments on purpose: launch
/// arguments are visible to anything that can list processes.
public struct DirectProviderKeyTransport: HTTPTransport {
    public static let anthropicKey = "SOAK_ANTHROPIC_API_KEY"
    public static let elevenLabsKey = "SOAK_ELEVENLABS_API_KEY"
    /// The drawing engines, so a live run can exercise generated pictures. Without them a soak
    /// sees only text — which is exactly the half of an app that was never slow.
    public static let geminiKey = "SOAK_GEMINI_API_KEY"
    public static let stabilityKey = "SOAK_STABILITY_API_KEY"
    public static let openAIKey = "SOAK_OPENAI_API_KEY"

    public var anthropic: String?
    public var elevenLabs: String?
    public var gemini: String?
    public var stability: String?
    public var openAI: String?
    private let underlying: any HTTPTransport

    public init(
        anthropic: String? = nil,
        elevenLabs: String? = nil,
        gemini: String? = nil,
        stability: String? = nil,
        openAI: String? = nil,
        underlying: any HTTPTransport = URLSession.shared
    ) {
        self.anthropic = anthropic
        self.elevenLabs = elevenLabs
        self.gemini = gemini
        self.stability = stability
        self.openAI = openAI
        self.underlying = underlying
    }

    /// Present only when at least one key was supplied; `nil` otherwise, and the caller then falls
    /// back to the ordinary wallet-backed routing.
    public static func fromEnvironment(
        _ env: [String: String] = ProcessInfo.processInfo.environment
    ) -> DirectProviderKeyTransport? {
        func key(_ name: String) -> String? { env[name].flatMap { $0.isEmpty ? nil : $0 } }
        let transport = DirectProviderKeyTransport(
            anthropic: key(anthropicKey),
            elevenLabs: key(elevenLabsKey),
            gemini: key(geminiKey),
            stability: key(stabilityKey),
            openAI: key(openAIKey)
        )
        let keys = [transport.anthropic, transport.elevenLabs, transport.gemini, transport.stability, transport.openAI]
        return keys.contains { $0 != nil } ? transport : nil
    }

    /// Same as `fromEnvironment(_:)`, but sending through `underlying` — for tests.
    func sending(through underlying: any HTTPTransport) -> DirectProviderKeyTransport {
        DirectProviderKeyTransport(
            anthropic: anthropic, elevenLabs: elevenLabs, gemini: gemini,
            stability: stability, openAI: openAI, underlying: underlying
        )
    }

    public func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        var request = request
        switch request.url?.host {
        case "api.anthropic.com":
            guard let anthropic else { throw URLError(.userAuthenticationRequired) }
            request.setValue(anthropic, forHTTPHeaderField: "x-api-key")
        case "api.elevenlabs.io":
            guard let elevenLabs else { throw URLError(.userAuthenticationRequired) }
            request.setValue(elevenLabs, forHTTPHeaderField: "xi-api-key")
        case "generativelanguage.googleapis.com":
            guard let gemini else { throw URLError(.userAuthenticationRequired) }
            request.setValue(gemini, forHTTPHeaderField: "x-goog-api-key")
        case "api.stability.ai":
            guard let stability else { throw URLError(.userAuthenticationRequired) }
            request.setValue("Bearer \(stability)", forHTTPHeaderField: "Authorization")
        case "api.openai.com":
            guard let openAI else { throw URLError(.userAuthenticationRequired) }
            request.setValue("Bearer \(openAI)", forHTTPHeaderField: "Authorization")
        default:
            // Anything else — Wikipedia, Wikimedia, the proxy's own endpoints — goes as it is. A
            // proxy capability call will simply fail for want of a funded wallet, which is the
            // honest answer: there is no provider key that stands in for it.
            break
        }
        return try await underlying.data(for: request)
    }
}
