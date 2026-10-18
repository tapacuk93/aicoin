import XCTest
@testable import AICoinKit

/// Answers every request with one canned reply and records what it was sent.
private final class StubTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private let body: Data
    private let status: Int
    private var _requests: [URLRequest] = []

    init(_ body: String, status: Int = 200) {
        self.body = Data(body.utf8)
        self.status = status
    }

    var requests: [URLRequest] { lock.withLock { _requests } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        lock.withLock { _requests.append(request) }
        return (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

final class AICoinModelCatalogTests: XCTestCase {
    private let base = URL(string: "https://proxy.example")!

    /// The shape the proxy publishes, trimmed to the parts this reads plus one it does not.
    private let skills = #"""
    {"subjects":["code"],"providers":[],"routing":{},"observed_ms":{},"escalation":true,
     "models":{"text":{"anthropic":"claude-sonnet-5","openai":"gpt-5"},
               "image":{"openai":"gpt-image-1"},
               "audio":{"elevenlabs":"eleven_multilingual_v2"},
               "consortium":{"panel":["openai:gpt-5","anthropic:claude-sonnet-5"],"editor":"anthropic"}}}
    """#

    func testFetchReadsTheModelsFromSkills() async throws {
        let transport = StubTransport(skills)
        let catalog = try await AICoinModelCatalog.fetch(baseURL: base, transport: transport)

        XCTAssertEqual(catalog, AICoinModelCatalog(
            text: ["anthropic": "claude-sonnet-5", "openai": "gpt-5"],
            image: ["openai": "gpt-image-1"],
            audio: ["elevenlabs": "eleven_multilingual_v2"],
            consortiumPanel: ["openai:gpt-5", "anthropic:claude-sonnet-5"],
            consortiumEditor: "anthropic"))
        let request = try XCTUnwrap(transport.requests.first)
        XCTAssertEqual(request.url?.absoluteString, "https://proxy.example/skills")
        XCTAssertEqual(request.httpMethod, "GET")
        // Public endpoint: nothing identifying goes with it.
        XCTAssertNil(request.value(forHTTPHeaderField: "X-Api-Key"))
    }

    func testAnOlderProxyWithoutModelsIsAnEmptyCatalogNotAnError() async throws {
        let transport = StubTransport(#"{"subjects":[],"providers":[],"routing":{},"escalation":true}"#)
        let catalog = try await AICoinModelCatalog.fetch(baseURL: base, transport: transport)
        XCTAssertEqual(catalog, AICoinModelCatalog())
        XCTAssertNil(catalog.textSignature(forProvider: "anthropic"))
        XCTAssertNil(catalog.textSignature(forProvider: nil))
    }

    func testAMissingPartLeavesTheRestReadable() async throws {
        let transport = StubTransport(#"{"models":{"text":{"kimi":"kimi-k2.6"},"consortium":null}}"#)
        let catalog = try await AICoinModelCatalog.fetch(baseURL: base, transport: transport)
        XCTAssertEqual(catalog.text, ["kimi": "kimi-k2.6"])
        XCTAssertEqual(catalog.image, [:])
        XCTAssertEqual(catalog.consortiumPanel, [])
        XCTAssertNil(catalog.consortiumEditor)
    }

    func testANon2xxIsAServerErrorWithTheBodysMessage() async {
        let transport = StubTransport(#"{"error":"down for maintenance"}"#, status: 503)
        do {
            _ = try await AICoinModelCatalog.fetch(baseURL: base, transport: transport)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .server(status: 503, message: "down for maintenance"))
        }
    }

    func testABodyThatIsNotJSONIsMalformed() async {
        let transport = StubTransport("<html>gateway</html>")
        do {
            _ = try await AICoinModelCatalog.fetch(baseURL: base, transport: transport)
            XCTFail("expected an error")
        } catch {
            XCTAssertEqual(error as? AICoinError, .malformedResponse)
        }
    }

    func testTextSignatures() {
        let catalog = AICoinModelCatalog(
            text: ["anthropic": "claude-sonnet-5", "openai": "gpt-5"],
            consortiumPanel: ["openai:gpt-5", "anthropic:claude-sonnet-5"],
            consortiumEditor: "anthropic")

        XCTAssertEqual(catalog.textSignature(forProvider: "anthropic"), "anthropic/claude-sonnet-5")
        XCTAssertEqual(catalog.textSignature(forProvider: " OpenAI "), "openai/gpt-5")
        // Sorted, so the server reordering its ranking does not look like a new model.
        XCTAssertEqual(catalog.textSignature(forProvider: nil),
                       "consortium/anthropic:claude-sonnet-5+openai:gpt-5")
        XCTAssertEqual(catalog.textSignature(forProvider: "consortium"),
                       catalog.textSignature(forProvider: nil))
        // Routed per request: no one model to name.
        XCTAssertNil(catalog.textSignature(forProvider: "automatic"))
        // Not listed is unknown, never "changed".
        XCTAssertNil(catalog.textSignature(forProvider: "mistral"))
    }

    func testAResponseStampAndACatalogSignatureCompareEqual() {
        let catalog = AICoinModelCatalog(text: ["openai": "gpt-5"])
        XCTAssertEqual(AICoinModelCatalog.signature(provider: "openai", model: "gpt-5"),
                       catalog.textSignature(forProvider: "openai"))
    }
}
