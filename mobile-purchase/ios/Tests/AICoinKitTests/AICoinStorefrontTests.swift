import XCTest
@testable import AICoinKit

/// Records every request and answers 200 with a body every client here can read.
private final class RecordingTransport: HTTPLineStreamingTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [URLRequest] = []
    var requests: [URLRequest] { lock.withLock { _requests } }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        lock.withLock { _requests.append(request) }
        return (Data(#"{"answer":"ok","user_id":"ab","balance":3,"models":{}}"#.utf8),
                HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }

    func lines(for request: URLRequest) async throws -> (AsyncThrowingStream<String, Error>, URLResponse) {
        let (data, response) = try await data(for: request)
        return (wholeBodyLines(data), response)
    }
}

final class AICoinStorefrontTests: XCTestCase {
    private let base = URL(string: "https://proxy.example")!

    override func tearDown() {
        AICoinStorefront.override(nil)
        super.tearDown()
    }

    func testAllowsWithholdsOnlyOpenAIOnlyInChina() {
        XCTAssertFalse(AICoinStorefront.allows(proxyProvider: "openai", storefront: "CHN"))
        XCTAssertFalse(AICoinStorefront.allows(proxyProvider: " OpenAI ", storefront: "chn"))
        XCTAssertTrue(AICoinStorefront.allows(proxyProvider: "anthropic", storefront: "CHN"))
        XCTAssertTrue(AICoinStorefront.allows(proxyProvider: "openai", storefront: "USA"))
        XCTAssertTrue(AICoinStorefront.allows(proxyProvider: "openai", storefront: nil))
    }

    func testOverrideDecidesAndNilClears() {
        AICoinStorefront.override("chn")
        XCTAssertEqual(AICoinStorefront.countryCode, "CHN")
        XCTAssertTrue(AICoinStorefront.isChinaMainland)
        XCTAssertFalse(AICoinStorefront.allows(proxyProvider: "openai"))
        XCTAssertTrue(AICoinStorefront.allows(proxyProvider: "google"))

        AICoinStorefront.override("USA")
        XCTAssertEqual(AICoinStorefront.countryCode, "USA")
        XCTAssertFalse(AICoinStorefront.isChinaMainland)
        XCTAssertTrue(AICoinStorefront.allows(proxyProvider: "openai"))

        AICoinStorefront.override(nil)
        // Back to StoreKit (if anything in this process started it), else the region.
        XCTAssertEqual(AICoinStorefront.countryCode,
                       AICoinStorefront.resolve(overridden: nil, storeKit: AICoinStorefront.storeKitCode,
                                                 region: Locale.current.region?.identifier))
    }

    func testResolutionOrder() {
        XCTAssertEqual(AICoinStorefront.resolve(overridden: "CHN", storeKit: "USA", region: "US"), "CHN")
        XCTAssertEqual(AICoinStorefront.resolve(overridden: nil, storeKit: "USA", region: "CN"), "USA")
        XCTAssertEqual(AICoinStorefront.resolve(overridden: nil, storeKit: nil, region: "CN"), "CHN")
        XCTAssertNil(AICoinStorefront.resolve(overridden: nil, storeKit: nil, region: "US"))
        XCTAssertNil(AICoinStorefront.resolve(overridden: nil, storeKit: nil, region: nil))
        XCTAssertNil(AICoinStorefront.normalized("CN"))
        XCTAssertEqual(AICoinStorefront.normalized(" gbr "), "GBR")
    }

    func testApplySetsTheHeaderOnlyWhenKnown() {
        AICoinStorefront.override("CHN")
        var request = URLRequest(url: base)
        AICoinStorefront.apply(to: &request)
        XCTAssertEqual(request.value(forHTTPHeaderField: AICoinStorefront.headerName), "CHN")
        XCTAssertEqual(AICoinStorefront.headerName, "X-AICoin-Storefront")
    }

    func testApplyLeavesTheRequestAloneWithoutAStorefront() {
        var request = URLRequest(url: base)
        AICoinStorefront.apply(nil, to: &request)
        XCTAssertNil(request.value(forHTTPHeaderField: AICoinStorefront.headerName))
    }

    func testEveryProxyRequestCarriesIt() async throws {
        AICoinStorefront.override("CHN")
        let transport = RecordingTransport()
        let router = AICoinRouter(underlying: transport, baseURL: base, eventBus: AICoinEventBus(),
                                  tokenProvider: { "tok" })
        // A provider call, rewritten onto the proxy.
        _ = try await router.data(for: URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!))
        // A proxy call passing through the router, by data and by lines.
        _ = try await router.data(for: URLRequest(url: base.appendingPathComponent("price")))
        _ = try await router.lines(for: URLRequest(url: base.appendingPathComponent("consortium")))
        // The capability client, the wallet client and the model catalog.
        let client = AICoinCapabilityClient(transport: transport, baseURL: base, eventBus: AICoinEventBus(),
                                            token: { "tok" })
        _ = try await client.text(prompt: "hi")
        _ = try await WalletClient(baseURL: base, transport: transport).balance(address: "ab")
        _ = try await AICoinModelCatalog.fetch(baseURL: base, transport: transport)

        XCTAssertEqual(transport.requests.count, 6)
        for request in transport.requests {
            XCTAssertEqual(request.value(forHTTPHeaderField: AICoinStorefront.headerName), "CHN",
                           request.url?.absoluteString ?? "")
        }
    }

    func testANonProxyHostPassingThroughTheRouterIsNotLabelled() async throws {
        AICoinStorefront.override("CHN")
        let transport = RecordingTransport()
        let router = AICoinRouter(underlying: transport, baseURL: base, eventBus: AICoinEventBus(),
                                  tokenProvider: { "tok" })
        _ = try await router.data(for: URLRequest(url: URL(string: "https://example.com/x")!))
        XCTAssertNil(transport.requests[0].value(forHTTPHeaderField: AICoinStorefront.headerName))
    }
}
