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
        /// `/consortium` only: `"standard"` or `"best"` — which models were seated.
        public let quality: String?

        private enum CodingKeys: String, CodingKey {
            case answer, provider, editor, model, escalated, subject, calls, settled, mode, rounds, quality
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
            quality = try c.decodeIfPresent(String.self, forKey: .quality)
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
        escalate: Bool? = nil, maxTokens: Int? = nil, timeout: TimeInterval = 120,
        fastest: Bool? = nil
    ) async throws -> TextAnswer {
        var body: [String: Any] = ["prompt": prompt]
        // Whoever answers soonest among the providers rated for it, rather than the best-rated:
        // for short requests someone is waiting on.
        if let fastest { body["fastest"] = fastest }
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
    ///
    /// - Parameters:
    ///   - library: the larger work this request belongs to; the panel sees its index and reads
    ///     earlier parts on demand (see `ConsortiumLibrary`).
    ///   - quality: `.best` seats each provider's strongest model, at several times the price.
    ///   - maxOutputTokens: the per-turn output cap. A long answer needs more than the proxy's
    ///     default, or it comes back cut off; the proxy clamps it to its own ceiling.
    ///   - onEvent: given, the call is streamed and each step of the panel's work is delivered as
    ///     it happens, on whatever task reads the stream; the return value is the same either way.
    ///     With streaming the timeout is the longest silence allowed — the proxy pings every 15 s.
    ///   - callID: makes the call durable. The proxy runs it to the end whether or not anyone is
    ///     listening, and survives its own restarts; calling again with the same id — after the
    ///     app was frozen in the background, or the stream dropped — attaches to that call instead
    ///     of starting a new one: its events so far are replayed (each with its `seq`, so ones
    ///     already shown can be skipped), then the rest arrive live, or the stored result comes
    ///     back at once if it has finished. Nothing is charged again. Generate it once per piece of
    ///     work (a UUID) and keep it until the result is in hand. The other arguments are ignored
    ///     when the id already names a call.
    ///   - notify: with a `callID`, push this when the call ends while no client is attached —
    ///     see `ConsortiumNotify`.
    ///   - idleTimeout: streaming only. The proxy sends a line at least every 15 s, so this long
    ///     with none means the connection is dead even if nothing said so (a proxy restart can
    ///     leave a stream silent rather than closed). It then throws an error
    ///     `isStreamStalled(_:)` recognises — a `URLError(.networkConnectionLost)`, which retry
    ///     policies already treat as transient. With a `callID`, retrying simply re-attaches.
    public func consortium(
        prompt: String, context: String? = nil, mode: String = "auto",
        maxRounds: Int? = nil, timeout: TimeInterval = 600,
        library: ConsortiumLibrary? = nil, quality: ConsortiumQuality? = nil,
        maxOutputTokens: Int? = nil, callID: String? = nil, idleTimeout: TimeInterval = 45,
        notify: ConsortiumNotify? = nil,
        onEvent: (@Sendable (ConsortiumEvent) -> Void)? = nil
    ) async throws -> TextAnswer {
        var body: [String: Any] = ["prompt": prompt, "mode": mode]
        if let callID { body["call_id"] = callID }
        // Only a durable call is told about later; the proxy ignores `notify` without an id.
        if let notify, callID != nil { body["notify"] = notify.json }
        if let context, !context.isEmpty { body["context"] = context }
        if let maxRounds { body["max_rounds"] = maxRounds }
        if let library { body["library"] = library.json }
        if let quality { body["quality"] = quality.rawValue }
        if let maxOutputTokens { body["max_output_tokens"] = maxOutputTokens }
        guard let onEvent else {
            let data = try await post("consortium", body: body, timeout: timeout)
            return try decodeAnswer(data)
        }
        body["stream"] = true
        return try await streamConsortium(body: body, timeout: timeout, idleTimeout: idleTimeout, onEvent: onEvent)
    }

    /// The `userInfo` key marking the `URLError` a silent stream is reported with.
    public static let streamStalledKey = "AICoinStreamStalled"

    /// Whether `error` is a streamed consortium that went silent past its `idleTimeout`. Also a
    /// `URLError(.networkConnectionLost)`, so code that only knows transport errors retries it.
    public static func isStreamStalled(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        return urlError.code == .networkConnectionLost && urlError.userInfo[streamStalledKey] as? Bool == true
    }

    static func streamStalled(after seconds: TimeInterval) -> URLError {
        URLError(.networkConnectionLost, userInfo: [
            streamStalledKey: true,
            NSLocalizedDescriptionKey: "The consortium stream went silent for \(Int(seconds)) seconds.",
        ])
    }

    /// A streamed consortium: NDJSON, one event per line, ending in a `result` line carrying the
    /// same body a plain call returns — or an `error` line with the status it would have had.
    /// Refusals decided before the panel starts (no token, an empty wallet, a bad body) are
    /// ordinary HTTP errors, exactly as without streaming.
    private func streamConsortium(
        body: [String: Any], timeout: TimeInterval, idleTimeout: TimeInterval,
        onEvent: @escaping @Sendable (ConsortiumEvent) -> Void
    ) async throws -> TextAnswer {
        let request = try makeRequest("consortium", body: body, timeout: timeout)
        let lines: AsyncThrowingStream<String, Error>
        let response: URLResponse
        if let streaming = transport as? any HTTPLineStreamingTransport {
            (lines, response) = try await streaming.lines(for: request)
        } else {
            // A transport that can only hand over whole bodies still gets every event, just
            // together at the end.
            let (data, whole) = try await transport.data(for: request)
            response = whole
            lines = wholeBodyLines(data)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            var collected = Data()
            for try await line in lines { collected.append(Data((line + "\n").utf8)) }
            try check(status: status, data: collected)
            throw AICoinError.server(status: status, message: Self.errorMessage(from: collected))
        }
        // The reader and a watchdog race: whichever finishes first decides. The watchdog only
        // fires after `idleTimeout` with no line at all — pings count — and cancelling the reader
        // ends the underlying request.
        let clock = LastLineClock()
        return try await withThrowingTaskGroup(of: TextAnswer.self) { group in
            group.addTask {
                for try await line in lines {
                    clock.touch()
                    if let answer = try self.handleStreamLine(line, onEvent: onEvent) { return answer }
                }
                // The connection ended without a last line: the proxy went away mid-call.
                throw AICoinError.malformedResponse
            }
            group.addTask {
                let step = min(1.0, max(idleTimeout / 4, 0.01))
                while true {
                    try await Task.sleep(nanoseconds: UInt64(step * 1_000_000_000))
                    if clock.secondsSinceLast() >= idleTimeout {
                        throw Self.streamStalled(after: idleTimeout)
                    }
                }
            }
            defer { group.cancelAll() }
            guard let answer = try await group.next() else { throw AICoinError.malformedResponse }
            return answer
        }
    }

    /// One NDJSON line: an event to deliver (nil returned), the result (returned), or the error
    /// the plain call would have thrown (thrown).
    private func handleStreamLine(_ line: String, onEvent: @Sendable (ConsortiumEvent) -> Void) throws -> TextAnswer? {
        let trimmed = line.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        else { return nil }
        switch object["event"] as? String {
        case "result":
            let answer = try decodeAnswer(data)
            eventBus.events.send(.paidCallSucceeded)
            return answer
        case "error":
            let failed = (object["status"] as? NSNumber)?.intValue ?? 502
            try check(status: failed, data: data)
            // Coins were spent on the turns that ran before it failed.
            eventBus.events.send(.paidCallSucceeded)
            throw AICoinError.server(status: failed, message: Self.errorMessage(from: data))
        default:
            if let event = ConsortiumEvent.parse(object) { onEvent(event) }
            return nil
        }
    }

    // MARK: - Push devices

    /// `POST /push/devices`: tell this wallet's durable calls (see `consortium(callID:notify:)`) to
    /// push to this device when they end unwatched. Free. `token` is what
    /// `didRegisterForRemoteNotificationsWithDeviceToken` hands over; `topic` is the app's bundle
    /// id; `environment` is `"sandbox"` for a development-signed build, `"production"` for
    /// TestFlight and the App Store — the APNs host that can reach the device.
    public func registerPushDevice(token: Data, topic: String, environment: String) async throws {
        _ = try await sendFree("push/devices", method: "POST",
                               body: Self.pushBody(token: token, topic: topic, environment: environment))
    }

    /// `DELETE /push/devices`: stop pushing to this device for this wallet (sign-out, a new wallet).
    public func unregisterPushDevice(token: Data, topic: String, environment: String) async throws {
        _ = try await sendFree("push/devices", method: "DELETE",
                               body: Self.pushBody(token: token, topic: topic, environment: environment))
    }

    static func pushBody(token: Data, topic: String, environment: String) -> [String: Any] {
        ["token": token.map { String(format: "%02x", $0) }.joined(), "topic": topic, "environment": environment]
    }

    /// A call that costs nothing: same token and errors as a paid one, but no "balance changed".
    private func sendFree(_ path: String, method: String, body: [String: Any]) async throws -> Data {
        var request = try makeRequest(path, body: body, timeout: 30)
        request.httpMethod = method
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw AICoinError.server(status: status, message: Self.errorMessage(from: data))
        }
        return data
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
        let request = try makeRequest(path, body: body, timeout: timeout)
        let (data, response) = try await transport.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        try check(status: status, data: data)
        guard (200..<300).contains(status) else {
            throw AICoinError.server(status: status, message: Self.errorMessage(from: data))
        }
        eventBus.events.send(.paidCallSucceeded)
        return data
    }

    /// A `402` as the typed error every caller handles, announced on the bus so the buy sheet
    /// opens — the one status this path has to translate itself.
    private func check(status: Int, data: Data) throws {
        if status == 402 {
            let balance = AICoinRouter.parseBalance(from: data)
            eventBus.events.send(.insufficientBalance(balance: balance))
            throw AICoinError.insufficientBalance(balance: balance)
        }
    }

    private func makeRequest(_ path: String, body: [String: Any], timeout: TimeInterval) throws -> URLRequest {
        guard let token = token(), !token.isEmpty else { throw AICoinError.missingToken }
        var request = URLRequest(url: WalletClient.endpointURL(baseURL, "/\(path)"))
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(token, forHTTPHeaderField: "X-Api-Key")
        // Slashes unescaped: the proxy reads bodies with a YAML parser, which has no `\/`, and a
        // prompt with a "/" in it came back 400 "body must be JSON".
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys, .withoutEscapingSlashes])
        return request
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

/// When a stream last delivered a line, readable from the watchdog task.
private final class LastLineClock: @unchecked Sendable {
    private let lock = NSLock()
    private var last = Date()

    func touch() { lock.withLock { last = Date() } }
    func secondsSinceLast() -> TimeInterval { lock.withLock { Date().timeIntervalSince(last) } }
}
