import Foundation

/// Calls to the proxy's own endpoints — `POST /text`, `/consortium`, `/image`, `/audio` — rather
/// than to a provider's API re-hosted by `AICoinRouter`.
///
/// Every app that used these wrote the same thirty lines by hand: All Languages Learner's
/// `ClaudeClient.sendThroughProxy` for text and `ProxySpeechClient` for speech. These endpoints
/// belong to the proxy itself, so there is no provider host for `AICoinRouter` to recognise, which
/// means none of what it does happens on its own: the wallet token has to go on as `X-Api-Key` by
/// hand, a `402` has to be turned into `AICoinError.insufficientBalance` here, and the event bus
/// has to be told — both apps had a comment saying so, because the first version of each forgot.
///
/// Errors:
/// - no token → `AICoinError.missingToken`, before anything is sent;
/// - `402` → `AICoinError.insufficientBalance`, also published on `eventBus`;
/// - any other non-2xx → `AICoinError.server(status:message:)`, where `message` is the body's
///   `error` field, or the raw body when it has none (so a caller deciding whether to retry a
///   `503` still has the status, and a person reading a log still has the words);
/// - a 2xx without the expected field → `AICoinError.malformedResponse`.
///
/// Transport errors (timeouts, dropped connections) are passed through untouched. Nothing here
/// retries: whether a failure is worth repeating is the caller's policy (see AITextKit).
public struct AICoinCapabilityClient: Sendable {
    private let transport: any HTTPTransport
    private let baseURL: URL
    private let eventBus: AICoinEventBus
    private let token: @Sendable () -> String?

    public init(
        transport: any HTTPTransport = URLSession.shared,
        baseURL: URL = AICoinConfig.defaultBaseURL,
        eventBus: AICoinEventBus = .shared,
        token: @escaping @Sendable () -> String?
    ) {
        self.transport = transport
        self.baseURL = baseURL
        self.eventBus = eventBus
        self.token = token
    }

    /// What `/text` and `/consortium` answer with — the field every caller needs, plus the
    /// routing and cost fields a caller might want to show. `/consortium` names an `editor`
    /// rather than a `provider`; `provider` carries it, since the editor is who wrote the answer.
    public struct TextAnswer: Decodable, Sendable {
        public let answer: String
        public let provider: String?
        public let model: String?
        public let coinsCharged: Double?
        /// `/text` only: true when the single model asked for the whole panel instead.
        public let escalated: Bool?
        /// The subject the request was ranked on.
        public let subject: String?
        public let calls: Int?
        /// `/consortium` only: whether a review round came back clean, and why it stopped.
        public let settled: Bool?
        public let stoppedReason: String?
        public let mode: String?
        public let rounds: Int?

        private enum CodingKeys: String, CodingKey {
            case answer, provider, editor, model, escalated, subject, calls, settled, mode, rounds
            case coinsCharged = "coins_charged"
            case stoppedReason = "stopped_reason"
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            answer = try c.decode(String.self, forKey: .answer)
            provider = try c.decodeIfPresent(String.self, forKey: .provider)
                ?? c.decodeIfPresent(String.self, forKey: .editor)
            model = try c.decodeIfPresent(String.self, forKey: .model)
            coinsCharged = try c.decodeIfPresent(Double.self, forKey: .coinsCharged)
            escalated = try c.decodeIfPresent(Bool.self, forKey: .escalated)
            subject = try c.decodeIfPresent(String.self, forKey: .subject)
            calls = try c.decodeIfPresent(Int.self, forKey: .calls)
            settled = try c.decodeIfPresent(Bool.self, forKey: .settled)
            stoppedReason = try c.decodeIfPresent(String.self, forKey: .stoppedReason)
            mode = try c.decodeIfPresent(String.self, forKey: .mode)
            rounds = try c.decodeIfPresent(Int.self, forKey: .rounds)
        }
    }

    /// `/image` and `/audio`: the proxy always answers base64 in JSON (`media_base64`,
    /// `media_type`), whoever generated it — decoded here so callers just get bytes.
    public struct Media: Sendable {
        public let data: Data
        public let mediaType: String
        public let provider: String?

        public init(data: Data, mediaType: String, provider: String?) {
            self.data = data
            self.mediaType = mediaType
            self.provider = provider
        }
    }

    // MARK: - Endpoints

    /// `POST /text`. `provider` pins one (the proxy never silently replaces a pinned provider);
    /// nil lets the proxy rank them for the request's subject. `maxTokens` is sent as
    /// `max_tokens` — the proxy currently applies its own per-turn cap and ignores it, but it
    /// costs nothing to say and is what the learner always sent.
    public func text(
        prompt: String, context: String? = nil, subject: String? = nil, provider: String? = nil,
        escalate: Bool? = nil, maxTokens: Int? = nil, timeout: TimeInterval = 120
    ) async throws -> TextAnswer {
        var body: [String: Any] = ["prompt": prompt]
        if let context, !context.isEmpty { body["context"] = context }
        if let subject { body["subject"] = subject }
        if let provider { body["provider"] = provider }
        if let escalate { body["escalate"] = escalate }
        if let maxTokens { body["max_tokens"] = maxTokens }
        let data = try await post("text", body: body, timeout: timeout)
        return try decodeAnswer(data)
    }

    /// `POST /consortium`. Minutes, not seconds — hence the ten-minute default timeout; a
    /// sixty-second one would abandon every panel mid-review, after its coins were spent.
    public func consortium(
        prompt: String, context: String? = nil, mode: String = "auto",
        maxRounds: Int? = nil, timeout: TimeInterval = 600
    ) async throws -> TextAnswer {
        var body: [String: Any] = ["prompt": prompt, "mode": mode]
        if let context, !context.isEmpty { body["context"] = context }
        if let maxRounds { body["max_rounds"] = maxRounds }
        let data = try await post("consortium", body: body, timeout: timeout)
        return try decodeAnswer(data)
    }

    /// `POST /image`. `size` is the provider-neutral `"1024x1024"` form.
    public func image(
        prompt: String, provider: String? = nil, size: String? = nil, timeout: TimeInterval = 120
    ) async throws -> Media {
        var body: [String: Any] = ["prompt": prompt]
        if let provider { body["provider"] = provider }
        if let size { body["size"] = size }
        let data = try await post("image", body: body, timeout: timeout)
        return try Self.media(from: data)
    }

    /// `POST /audio`. Always MP3 back, whoever spoke. `language` matters only to Google Cloud
    /// Text-to-Speech, which will not infer it; the others read it from the text.
    public func audio(
        prompt: String, provider: String? = nil, voice: String? = nil, language: String? = nil,
        timeout: TimeInterval = 120
    ) async throws -> Media {
        var body: [String: Any] = ["prompt": prompt]
        if let provider { body["provider"] = provider }
        if let voice { body["voice"] = voice }
        if let language { body["language"] = language }
        let data = try await post("audio", body: body, timeout: timeout)
        return try Self.media(from: data)
    }

    // MARK: - Plumbing

    private func post(_ path: String, body: [String: Any], timeout: TimeInterval) async throws -> Data {
        guard let token = token(), !token.isEmpty else { throw AICoinError.missingToken }
        var request = URLRequest(url: WalletClient.endpointURL(baseURL, "/\(path)"))
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "X-Api-Key")
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])

        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 402 {
            let balance = AICoinRouter.parseBalance(from: data)
            eventBus.events.send(.insufficientBalance(balance: balance))
            throw AICoinError.insufficientBalance(balance: balance)
        }
        guard (200..<300).contains(status) else {
            throw AICoinError.server(status: status, message: Self.errorMessage(from: data))
        }
        eventBus.events.send(.paidCallSucceeded)
        return data
    }

    private func decodeAnswer(_ data: Data) throws -> TextAnswer {
        do {
            return try JSONDecoder().decode(TextAnswer.self, from: data)
        } catch {
            throw AICoinError.malformedResponse
        }
    }

    /// `{"media_base64": "...", "media_type": "audio/mpeg", "provider": ...}` — the proxy's one
    /// media shape. Empty bytes are a failure, not silence.
    public static func media(from body: Data) throws -> Media {
        struct Envelope: Decodable {
            let media_base64: String
            let media_type: String?
            let provider: String?
        }
        guard
            let envelope = try? JSONDecoder().decode(Envelope.self, from: body),
            let bytes = Data(base64Encoded: envelope.media_base64, options: .ignoreUnknownCharacters),
            !bytes.isEmpty
        else { throw AICoinError.malformedResponse }
        return Media(data: bytes, mediaType: envelope.media_type ?? "application/octet-stream", provider: envelope.provider)
    }

    /// The body's `error` field when it has one, otherwise the body itself (trimmed, capped) —
    /// nil only for an empty body.
    static func errorMessage(from data: Data) -> String? {
        struct Body: Decodable { let error: String? }
        if let message = (try? JSONDecoder().decode(Body.self, from: data))?.error, !message.isEmpty {
            return message
        }
        let raw = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        return raw.isEmpty ? nil : String(raw.prefix(500))
    }
}

extension AICoinError {
    /// Wallet errors no retry will clear — an empty wallet, or no wallet token at all. Every
    /// retry loop over a paid call wants exactly this test (NarrationKit's `RetryPolicy.isTerminal`,
    /// AITextKit's backoff, an image engine chain): repeating one of these only fails the same way,
    /// and an empty wallet would be asked to pay again each time.
    ///
    /// `server` and `malformedResponse` are deliberately not terminal: a `503` from the proxy is
    /// as transient as one from anywhere else.
    public static func isTerminal(_ error: Error) -> Bool {
        switch error as? AICoinError {
        case .insufficientBalance, .missingToken: return true
        default: return false
        }
    }
}
