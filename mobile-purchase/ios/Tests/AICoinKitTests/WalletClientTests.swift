import XCTest
@testable import AICoinKit

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

final class WalletClientTests: XCTestCase {
    private let baseURL = URL(string: "https://proxy.aicoin.oeaio.com")!

    // MARK: - endpointURL path joining (what WalletSigner's canonical message must match)

    func testEndpointURLProducesExactlyThePathWithNoDoubleSlashes() {
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/claim")
        XCTAssertEqual(url.absoluteString, "https://proxy.aicoin.oeaio.com/wallet/api/claim")
        XCTAssertEqual(url.path, "/wallet/api/claim")
    }

    // MARK: - balance()

    func testBalanceDecodesTheDocumentedShape() async throws {
        let address = "aa".repeated(32)
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/balance/\(address)")
        let body = Data(#"{"user_id":"\#(address)","balance":42.5}"#.utf8)
        let transport = MockHTTPTransport([(body, httpResponse(url, statusCode: 200))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        let balance = try await client.balance(address: address)

        XCTAssertEqual(balance, 42.5)
        XCTAssertEqual(transport.requests[0].url, url)
        XCTAssertEqual(transport.requests[0].httpMethod, "GET")
    }

    func testBalanceThrowsServerErrorOnNon2xx() async throws {
        let address = "aa".repeated(32)
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/balance/\(address)")
        let transport = MockHTTPTransport([(Data(#"{"error":"boom"}"#.utf8), httpResponse(url, statusCode: 500))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        do {
            _ = try await client.balance(address: address)
            XCTFail("expected error")
        } catch AICoinError.server(let status, let message) {
            XCTAssertEqual(status, 500)
            XCTAssertEqual(message, "boom")
        }
    }

    // MARK: - claimFreeCoins()

    func testClaimGrantedDecodesAmountAndSendsLiveSignatureHeaders() async throws {
        let identity = WalletIdentity.generate()
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/claim")
        let body = Data(#"{"granted":true,"amount":10,"next_eligible_at":"2026-01-01T00:00:00Z"}"#.utf8)
        let transport = MockHTTPTransport([(body, httpResponse(url, statusCode: 200))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        let result = try await client.claimFreeCoins(identity: identity)

        XCTAssertTrue(result.granted)
        XCTAssertEqual(result.amount, 10)
        XCTAssertNotNil(result.nextEligibleAt)

        let sent = transport.requests[0]
        XCTAssertEqual(sent.httpMethod, "POST")
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-Api-Key"), identity.address)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-Api-Signature")?.count, 128)
        XCTAssertNotNil(sent.value(forHTTPHeaderField: "X-Api-Timestamp"))
        XCTAssertNil(sent.httpBody, "claim needs no body per CONTRACT.md")
    }

    func testClaimCooldownDecodesReasonWithoutThrowing() async throws {
        let identity = WalletIdentity.generate()
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/claim")
        let body = Data(#"{"granted":false,"reason":"cooldown","next_eligible_at":"2026-01-01T00:00:00Z"}"#.utf8)
        let transport = MockHTTPTransport([(body, httpResponse(url, statusCode: 429))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        let result = try await client.claimFreeCoins(identity: identity)

        XCTAssertFalse(result.granted)
        XCTAssertEqual(result.reason, "cooldown")
        XCTAssertNotNil(result.nextEligibleAt)
    }

    func testClaimPoolExhaustedHasNoNextEligibleAt() async throws {
        let identity = WalletIdentity.generate()
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/claim")
        let body = Data(#"{"granted":false,"reason":"pool_exhausted"}"#.utf8)
        let transport = MockHTTPTransport([(body, httpResponse(url, statusCode: 429))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        let result = try await client.claimFreeCoins(identity: identity)

        XCTAssertFalse(result.granted)
        XCTAssertEqual(result.reason, "pool_exhausted")
        XCTAssertNil(result.nextEligibleAt)
    }

    // MARK: - transfer()

    func testTransferSendsCorrectBodyAndLiveSignatureOverThatExactBody() async throws {
        let identity = WalletIdentity.generate()
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/transfer")
        let transport = MockHTTPTransport([(Data(#"{"balance":5}"#.utf8), httpResponse(url, statusCode: 200))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        let result = try await client.transfer(to: "bb".repeated(32), amount: 3.5, identity: identity)

        XCTAssertEqual(result.balance, 5)
        let sent = transport.requests[0]
        let decodedBody = try JSONDecoder().decode(TransferRequestBody.self, from: sent.httpBody!)
        XCTAssertEqual(decodedBody.toUserId, "bb".repeated(32))
        XCTAssertEqual(decodedBody.amount, 3.5)

        // The signature must cover this exact body — re-derive and check it verifies.
        let timestamp = Int64(sent.value(forHTTPHeaderField: "X-Api-Timestamp")!)!
        let message = WalletSigner.canonicalMessage(
            address: identity.address, timestampMillis: timestamp, method: "POST", path: "/wallet/api/transfer", body: sent.httpBody!
        )
        let signatureHex = sent.value(forHTTPHeaderField: "X-Api-Signature")!
        var sigBytes = Data(capacity: 64)
        var idx = signatureHex.startIndex
        while idx < signatureHex.endIndex {
            let next = signatureHex.index(idx, offsetBy: 2)
            sigBytes.append(UInt8(signatureHex[idx..<next], radix: 16)!)
            idx = next
        }
        XCTAssertTrue(identity.privateKey.publicKey.isValidSignature(sigBytes, for: message))
    }

    func testTransferThrowsInsufficientBalanceServerError() async throws {
        let identity = WalletIdentity.generate()
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/transfer")
        let transport = MockHTTPTransport([(Data(#"{"error":"insufficient balance"}"#.utf8), httpResponse(url, statusCode: 400))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        do {
            _ = try await client.transfer(to: "cc".repeated(32), amount: 999, identity: identity)
            XCTFail("expected error")
        } catch AICoinError.server(let status, let message) {
            XCTAssertEqual(status, 400)
            XCTAssertEqual(message, "insufficient balance")
        }
    }

    // MARK: - revokeTokens()

    func testRevokeTokensSendsLiveSignedRequestWithNoBody() async throws {
        let identity = WalletIdentity.generate()
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/revoke-tokens")
        let transport = MockHTTPTransport([(Data(), httpResponse(url, statusCode: 200))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        try await client.revokeTokens(identity: identity)

        let sent = transport.requests[0]
        XCTAssertNil(sent.httpBody)
        XCTAssertEqual(sent.value(forHTTPHeaderField: "X-Api-Key"), identity.address)
    }

    // MARK: - redeemIAP()

    func testRedeemIAPPostsExactlyTheDocumentedBodyUnsigned() async throws {
        let address = "dd".repeated(32)
        let url = WalletClient.endpointURL(baseURL, "/wallet/api/redeem-iap")
        let transport = MockHTTPTransport([(Data(#"{"credited":50,"balance":50}"#.utf8), httpResponse(url, statusCode: 200))])
        let client = WalletClient(baseURL: baseURL, transport: transport)

        let result = try await client.redeemIAP(to: address, signedTransaction: "jws-blob")

        XCTAssertEqual(result.credited, 50)
        XCTAssertEqual(result.balance, 50)
        let sent = transport.requests[0]
        XCTAssertNil(sent.value(forHTTPHeaderField: "X-Api-Signature"), "redeem-iap is unsigned per CONTRACT.md")
        let decoded = try JSONDecoder().decode(RedeemIAPRequestBody.self, from: sent.httpBody!)
        XCTAssertEqual(decoded.toUserId, address)
        XCTAssertEqual(decoded.signedTransaction, "jws-blob")
    }
}

private extension String {
    func repeated(_ n: Int) -> String { String(repeating: self, count: n) }
}
