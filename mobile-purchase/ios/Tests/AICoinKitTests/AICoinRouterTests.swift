import XCTest
@testable import AICoinKit

/// Records every request it's given and replays canned responses in order — same shape as the
/// mock transports already used in All Languages Learner's and Learn It's own router tests, so
/// this suite reads the same way.
private final class MockHTTPTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var responses: [(Data, URLResponse)]
    private var _requests: [URLRequest] = []

    init(_ responses: [(Data, URLResponse)]) {
        self.responses = responses
    }

    var requests: [URLRequest] {
        lock.withLock { _requests }
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        lock.withLock { _requests.append(request) }
        guard !responses.isEmpty else {
            XCTFail("MockHTTPTransport ran out of queued responses")
            return (Data(), URLResponse())
        }
        return lock.withLock { responses.removeFirst() }
    }
}

private func httpResponse(_ url: URL, statusCode: Int) -> HTTPURLResponse {
    HTTPURLResponse(url: url, statusCode: statusCode, httpVersion: nil, headerFields: nil)!
}

private let anthropicURL = URL(string: "https://api.anthropic.com/v1/messages?foo=bar")!
private let elevenLabsURL = URL(string: "https://api.elevenlabs.io/v1/text-to-speech/voice123")!
private let unmappedURL = URL(string: "https://api.deepseek.com/v1/chat/completions")!

private func makeRequest(url: URL, body: String = #"{"hello":"world"}"#) -> URLRequest {
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.httpBody = Data(body.utf8)
    return request
}

final class AICoinRouterTests: XCTestCase {

    // MARK: - Passthrough cases

    func testPassthroughForHostTheProxyDoesNotKnowAbout() async throws {
        let transport = MockHTTPTransport([(Data(), httpResponse(unmappedURL, statusCode: 200))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "token" })

        _ = try await router.data(for: makeRequest(url: unmappedURL))

        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(transport.requests[0].url, unmappedURL)
    }

    func testUnmappedHostIsPassedThroughEvenWithNoTokenConfigured() async throws {
        let transport = MockHTTPTransport([(Data(), httpResponse(unmappedURL, statusCode: 200))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { nil })

        _ = try await router.data(for: makeRequest(url: unmappedURL))

        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(transport.requests[0].url, unmappedURL)
    }

    // MARK: - Missing token on a known host

    func testKnownHostWithNoTokenThrowsMissingTokenAndNeverCallsTransport() async throws {
        let transport = MockHTTPTransport([(Data(), httpResponse(anthropicURL, statusCode: 200))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { nil })

        do {
            _ = try await router.data(for: makeRequest(url: anthropicURL))
            XCTFail("expected AICoinError.missingToken")
        } catch AICoinError.missingToken {
            // expected
        }
        XCTAssertEqual(transport.requests.count, 0, "must not attempt any request without a token")
    }

    func testEmptyStringTokenIsTreatedAsMissing() async throws {
        let transport = MockHTTPTransport([(Data(), httpResponse(anthropicURL, statusCode: 200))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "" })

        do {
            _ = try await router.data(for: makeRequest(url: anthropicURL))
            XCTFail("expected AICoinError.missingToken")
        } catch AICoinError.missingToken {
            // expected
        }
    }

    // MARK: - Routing / rewriting

    func testRoutesKnownHostThroughProxyWithHeadersPreservingMethodPathQueryBody() async throws {
        let proxyURL = URL(string: "https://proxy.aicoin.oeaio.com/v1/messages")!
        let transport = MockHTTPTransport([(Data(#"{"ok":true}"#.utf8), httpResponse(proxyURL, statusCode: 200))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "the-token" })

        _ = try await router.data(for: makeRequest(url: anthropicURL))

        XCTAssertEqual(transport.requests.count, 1)
        let sent = transport.requests[0]
        XCTAssertEqual(sent.url?.host, "proxy.aicoin.oeaio.com")
        XCTAssertEqual(sent.url?.scheme, "https")
        XCTAssertEqual(sent.url?.path, "/v1/messages")
        XCTAssertEqual(sent.url?.query, "foo=bar")
        XCTAssertEqual(sent.httpMethod, "POST")
        XCTAssertEqual(sent.httpBody, Data(#"{"hello":"world"}"#.utf8))
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-AI"), "anthropic")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-Api-Key"), "the-token")
    }

    func testMapsElevenLabsHostToItsOwnXAIValue() async throws {
        let transport = MockHTTPTransport([(Data(), httpResponse(elevenLabsURL, statusCode: 200))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "tok" })

        _ = try await router.data(for: makeRequest(url: elevenLabsURL))

        let sent = transport.requests[0]
        XCTAssertEqual(sent.url?.host, "proxy.aicoin.oeaio.com")
        XCTAssertEqual(sent.url?.path, "/v1/text-to-speech/voice123")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-AI"), "elevenlabs")
    }

    func testEveryContractDocumentedProviderHostResolvesToTheRightXAIValue() {
        let expected: [String: AIProviderRoute] = [
            "api.anthropic.com": .anthropic,
            "api.openai.com": .openai,
            "generativelanguage.googleapis.com": .google,
            "api.mistral.ai": .mistral,
            "api.cohere.ai": .cohere,
            "api.elevenlabs.io": .elevenlabs,
            "api.stability.ai": .stability,
            "api.moonshot.ai": .kimi,
        ]
        XCTAssertEqual(AICoinRouter.knownHosts, expected)
        XCTAssertEqual(AICoinRouter.knownHosts.count, AIProviderRoute.allCases.count, "every provider CONTRACT.md lists should have exactly one host mapping")
    }

    // MARK: - 402 balance gate: throws, never falls back

    func test402ThrowsTypedInsufficientBalanceErrorWithParsedBalance() async throws {
        let proxyURL = URL(string: "https://proxy.aicoin.oeaio.com/v1/messages")!
        let body = Data(#"{"error":"insufficient aicoin balance","balance":0.5}"#.utf8)
        let transport = MockHTTPTransport([(body, httpResponse(proxyURL, statusCode: 402))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "tok" })

        do {
            _ = try await router.data(for: makeRequest(url: anthropicURL))
            XCTFail("expected AICoinError.insufficientBalance")
        } catch AICoinError.insufficientBalance(let balance) {
            XCTAssertEqual(balance, 0.5)
        }
        XCTAssertEqual(transport.requests.count, 1, "must not attempt a second, fallback request — no bring-your-own-key fallback in this design")
    }

    func test402WithUnparsableBodyStillThrowsWithNilBalance() async throws {
        let proxyURL = URL(string: "https://proxy.aicoin.oeaio.com/v1/messages")!
        let transport = MockHTTPTransport([(Data("not json".utf8), httpResponse(proxyURL, statusCode: 402))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "tok" })

        do {
            _ = try await router.data(for: makeRequest(url: anthropicURL))
            XCTFail("expected AICoinError.insufficientBalance")
        } catch AICoinError.insufficientBalance(let balance) {
            XCTAssertNil(balance)
        }
    }

    func test402PublishesInsufficientBalanceEventOnTheEventBus() async throws {
        let proxyURL = URL(string: "https://proxy.aicoin.oeaio.com/v1/messages")!
        let body = Data(#"{"error":"insufficient aicoin balance","balance":2}"#.utf8)
        let transport = MockHTTPTransport([(body, httpResponse(proxyURL, statusCode: 402))])
        let bus = AICoinEventBus()
        let router = AICoinRouter(underlying: transport, eventBus: bus, tokenProvider: { "tok" })

        var received: [AICoinEvent] = []
        let cancellable = bus.events.sink { received.append($0) }

        _ = try? await router.data(for: makeRequest(url: anthropicURL))
        cancellable.cancel()

        XCTAssertEqual(received, [.insufficientBalance(balance: 2)])
    }

    func testNonInsufficientBalanceErrorStatusIsReturnedAsIsNotThrown() async throws {
        let proxyURL = URL(string: "https://proxy.aicoin.oeaio.com/v1/messages")!
        let transport = MockHTTPTransport([(Data("boom".utf8), httpResponse(proxyURL, statusCode: 500))])
        let router = AICoinRouter(underlying: transport, eventBus: AICoinEventBus(), tokenProvider: { "tok" })

        let (data, response) = try await router.data(for: makeRequest(url: anthropicURL))

        XCTAssertEqual(data, Data("boom".utf8))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 500)
    }

    // MARK: - Success publishes paidCallSucceeded

    func testSuccessfulProxiedCallPublishesPaidCallSucceeded() async throws {
        let proxyURL = URL(string: "https://proxy.aicoin.oeaio.com/v1/messages")!
        let transport = MockHTTPTransport([(Data("ok".utf8), httpResponse(proxyURL, statusCode: 200))])
        let bus = AICoinEventBus()
        let router = AICoinRouter(underlying: transport, eventBus: bus, tokenProvider: { "tok" })

        var received: [AICoinEvent] = []
        let cancellable = bus.events.sink { received.append($0) }

        _ = try await router.data(for: makeRequest(url: anthropicURL))
        cancellable.cancel()

        XCTAssertEqual(received, [.paidCallSucceeded])
    }

    func testPassthroughRequestsDoNotPublishAnyEvent() async throws {
        let transport = MockHTTPTransport([(Data(), httpResponse(unmappedURL, statusCode: 200))])
        let bus = AICoinEventBus()
        let router = AICoinRouter(underlying: transport, eventBus: bus, tokenProvider: { "tok" })

        var received: [AICoinEvent] = []
        let cancellable = bus.events.sink { received.append($0) }

        _ = try await router.data(for: makeRequest(url: unmappedURL))
        cancellable.cancel()

        XCTAssertTrue(received.isEmpty)
    }
}
