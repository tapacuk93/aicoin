import XCTest
@testable import AICoinKit

/// Records every request and answers 200 with `{"answer":"ok"}`.
private final class RecordingTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    var requests: [URLRequest] { lock.withLock { _requests } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        lock.withLock { _requests.append(request) }
        return (Data(#"{"answer":"ok","media_base64":"aGk=","media_type":"audio/mpeg"}"#.utf8),
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

final class AICoinCallTagsTests: XCTestCase {
    private let base = URL(string: "https://proxy.example")!
    private let anthropic = URL(string: "https://api.anthropic.com/v1/messages")!

    private func router(_ transport: RecordingTransport) -> AICoinRouter {
        AICoinRouter(underlying: transport, baseURL: base, eventBus: AICoinEventBus(), tokenProvider: { "tok" })
    }

    private func client(_ transport: RecordingTransport) -> AICoinCapabilityClient {
        AICoinCapabilityClient(transport: transport, baseURL: base, eventBus: AICoinEventBus(), token: { "tok" })
    }

    func testRouterSendsNoPurposeWhenNoneIsGiven() async throws {
        let transport = RecordingTransport()
        _ = try await router(transport).data(for: URLRequest(url: anthropic))
        XCTAssertNil(transport.requests[0].value(forHTTPHeaderField: AICoinCallTags.purposeHeader))
    }

    func testRouterSendsThePurposeOfTheSurroundingTask() async throws {
        let transport = RecordingTransport()
        let router = router(transport)
        try await AICoinCallTags.withPurpose("narrate") {
            _ = try await router.data(for: URLRequest(url: anthropic))
            // A Task started inside inherits it, as a queue's worker would.
            _ = try await Task { try await router.data(for: URLRequest(url: anthropic)) }.value
        }
        _ = try await router.data(for: URLRequest(url: anthropic))
        XCTAssertEqual(transport.requests.map { $0.value(forHTTPHeaderField: AICoinCallTags.purposeHeader) },
                       ["narrate", "narrate", nil])
    }

    func testAPurposeTheRequestAlreadyCarriesWins() async throws {
        let transport = RecordingTransport()
        var request = URLRequest(url: anthropic)
        request.setValue("translate", forHTTPHeaderField: AICoinCallTags.purposeHeader)
        try await AICoinCallTags.withPurpose("narrate") {
            _ = try await router(transport).data(for: request)
        }
        XCTAssertEqual(transport.requests[0].value(forHTTPHeaderField: AICoinCallTags.purposeHeader), "translate")
    }

    func testAppHeaderIsTheBundleIDAndLeftAloneWhenSet() {
        var request = URLRequest(url: anthropic)
        AICoinCallTags.tag(&request, appID: "com.example.app")
        XCTAssertEqual(request.value(forHTTPHeaderField: AICoinCallTags.appHeader), "com.example.app")
        AICoinCallTags.tag(&request, appID: "com.other")
        XCTAssertEqual(request.value(forHTTPHeaderField: AICoinCallTags.appHeader), "com.example.app")
        var none = URLRequest(url: anthropic)
        AICoinCallTags.tag(&none, appID: nil)
        XCTAssertNil(none.value(forHTTPHeaderField: AICoinCallTags.appHeader))
    }

    func testPurposeIsSanitized() {
        XCTAssertNil(AICoinCallTags.sanitized("   "))
        XCTAssertNil(AICoinCallTags.sanitized(nil))
        XCTAssertEqual(AICoinCallTags.sanitized(" chat\n"), "chat")
        XCTAssertEqual(AICoinCallTags.sanitized(String(repeating: "x", count: 200))?.count,
                       AICoinCallTags.maxPurposeLength)
    }

    func testCapabilityClientArgumentBeatsTheTaskPurpose() async throws {
        let transport = RecordingTransport()
        let client = client(transport)
        try await AICoinCallTags.withPurpose("outer") {
            _ = try await client.text(prompt: "p", purpose: "lesson")
            _ = try await client.text(prompt: "p")
        }
        _ = try await client.text(prompt: "p")
        _ = try await client.audio(prompt: "p", purpose: "speak")
        XCTAssertEqual(transport.requests.map { $0.value(forHTTPHeaderField: AICoinCallTags.purposeHeader) },
                       ["lesson", "outer", nil, "speak"])
    }

    /// Every call site written before purposes existed still compiles and sends no purpose.
    func testExistingCallShapesStillCompile() async throws {
        let transport = RecordingTransport()
        let client = client(transport)
        _ = try await client.text(prompt: "p", context: nil, subject: nil, provider: nil, escalate: nil,
                                  maxTokens: nil, timeout: 10, fastest: nil)
        _ = try await client.consortium(prompt: "p", mode: "auto")
        _ = try await client.image(prompt: "p", provider: nil, size: nil, timeout: 5)
        XCTAssertNil(transport.requests[0].value(forHTTPHeaderField: AICoinCallTags.purposeHeader))
    }
}
