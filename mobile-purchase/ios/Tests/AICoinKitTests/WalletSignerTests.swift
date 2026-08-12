import XCTest
import CryptoKit
@testable import AICoinKit

/// Pure-logic tests for the Ed25519 signing scheme against CONTRACT.md's exact canonical message
/// format: `address + "\n" + timestampMillis + "\n" + httpMethod + "\n" + requestPath + "\n" +
/// hex(sha256(requestBody))`. No network access; keys are generated in-process.
final class WalletSignerTests: XCTestCase {

    // MARK: - Canonical message construction

    func testCanonicalMessageMatchesTheDocumentedFormatExactly() {
        let body = Data(#"{"to_user_id":"abc","amount":1}"#.utf8)
        let message = WalletSigner.canonicalMessage(
            address: "addr", timestampMillis: 42, method: "POST", path: "/wallet/api/transfer", body: body
        )
        let bodyHashHex = SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
        let expected = "addr\n42\nPOST\n/wallet/api/transfer\n\(bodyHashHex)"
        XCTAssertEqual(String(data: message, encoding: .utf8), expected)
    }

    func testCanonicalMessageOfAnEmptyBodyHashesTheEmptyByteString() {
        let message = WalletSigner.canonicalMessage(
            address: "addr", timestampMillis: 1, method: "POST", path: "/wallet/api/claim", body: Data()
        )
        let emptyHashHex = SHA256.hash(data: Data()).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(String(data: message, encoding: .utf8), "addr\n1\nPOST\n/wallet/api/claim\n\(emptyHashHex)")
        // Known SHA-256("") value, independent of our own hex helper, as a sanity cross-check.
        XCTAssertEqual(emptyHashHex, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    func testCanonicalMessageOfKnownVectorABC() {
        // NIST's well-known SHA-256("abc") test vector, used here to confirm our hex encoding of
        // the digest is byte-correct independent of the rest of the signing pipeline.
        let message = WalletSigner.canonicalMessage(
            address: "a", timestampMillis: 0, method: "GET", path: "/x", body: Data("abc".utf8)
        )
        let text = String(data: message, encoding: .utf8)!
        XCTAssertTrue(text.hasSuffix("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    // MARK: - Live signature round trip

    func testLiveSignatureVerifiesAgainstTheWalletsOwnPublicKey() throws {
        let identity = WalletIdentity.generate()
        let headers = try WalletSigner.signLiveRequest(identity: identity, method: "POST", path: "/wallet/api/claim", body: Data())

        XCTAssertEqual(headers.address, identity.address)
        XCTAssertEqual(headers.address.count, 64, "address must be 64 hex chars (32-byte raw public key)")
        XCTAssertEqual(headers.signatureHex.count, 128, "signature must be 128 hex chars (raw 64-byte R‖S, no DER)")

        let message = WalletSigner.canonicalMessage(
            address: headers.address, timestampMillis: headers.timestampMillis,
            method: "POST", path: "/wallet/api/claim", body: Data()
        )
        let signatureBytes = try XCTUnwrap(dataFromHex(headers.signatureHex))
        XCTAssertTrue(identity.privateKey.publicKey.isValidSignature(signatureBytes, for: message))
    }

    func testTamperingWithAnyCanonicalFieldBreaksVerification() throws {
        let identity = WalletIdentity.generate()
        let headers = try WalletSigner.signLiveRequest(identity: identity, method: "POST", path: "/wallet/api/claim", body: Data())
        let signatureBytes = try XCTUnwrap(dataFromHex(headers.signatureHex))

        let tamperedPath = WalletSigner.canonicalMessage(
            address: headers.address, timestampMillis: headers.timestampMillis,
            method: "POST", path: "/wallet/api/transfer", body: Data()
        )
        XCTAssertFalse(identity.privateKey.publicKey.isValidSignature(signatureBytes, for: tamperedPath))

        let tamperedTimestamp = WalletSigner.canonicalMessage(
            address: headers.address, timestampMillis: headers.timestampMillis + 1,
            method: "POST", path: "/wallet/api/claim", body: Data()
        )
        XCTAssertFalse(identity.privateKey.publicKey.isValidSignature(signatureBytes, for: tamperedTimestamp))
    }

    func testSignatureFromADifferentKeypairFailsVerification() throws {
        let identity = WalletIdentity.generate()
        let impostor = WalletIdentity.generate()
        let headers = try WalletSigner.signLiveRequest(identity: identity, method: "POST", path: "/wallet/api/claim", body: Data())
        let message = WalletSigner.canonicalMessage(
            address: headers.address, timestampMillis: headers.timestampMillis, method: "POST", path: "/wallet/api/claim", body: Data()
        )
        let signatureBytes = try XCTUnwrap(dataFromHex(headers.signatureHex))
        XCTAssertFalse(impostor.privateKey.publicKey.isValidSignature(signatureBytes, for: message))
    }

    // MARK: - applyLiveSignature(to:identity:) — the URLRequest integration point

    func testApplyLiveSignatureSetsExactlyTheThreeDocumentedHeaders() throws {
        var request = URLRequest(url: URL(string: "https://proxy.aicoin.oeaio.com/wallet/api/transfer?ignored=query")!)
        request.httpMethod = "POST"
        request.httpBody = Data(#"{"to_user_id":"x","amount":5}"#.utf8)
        let identity = WalletIdentity.generate()

        try WalletSigner.applyLiveSignature(to: &request, identity: identity)

        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Api-Key"), identity.address)
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Api-Signature")?.count, 128)
        XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Api-Timestamp"))

        // The signed path must exclude the query string, per CONTRACT.md ("requestPath has no
        // query string") — verify by reconstructing the message with `.path` and confirming it
        // validates.
        let timestamp = Int64(request.value(forHTTPHeaderField: "X-Api-Timestamp")!)!
        let message = WalletSigner.canonicalMessage(
            address: identity.address, timestampMillis: timestamp,
            method: "POST", path: "/wallet/api/transfer", body: request.httpBody!
        )
        let signatureBytes = try XCTUnwrap(dataFromHex(request.value(forHTTPHeaderField: "X-Api-Signature")!))
        XCTAssertTrue(identity.privateKey.publicKey.isValidSignature(signatureBytes, for: message))
    }

    // MARK: - API tokens

    func testIssuedTokenHasTheJwtShapedFormatAndVerifiesAgainstItsOwnPayload() throws {
        let identity = WalletIdentity.generate()
        let token = try WalletSigner.issueAPIToken(identity: identity, validFor: 3600)

        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        XCTAssertEqual(parts.count, 2)
        XCTAssertTrue(WalletSigner.verifyTokenSignature(token))

        let payload = try WalletSigner.decodeTokenPayload(token)
        XCTAssertEqual(payload.addr, identity.address)
        XCTAssertEqual(payload.exp - payload.iat, 3600)
        XCTAssertFalse(payload.isExpired)
    }

    func testExpiredTokenPayloadReportsExpired() throws {
        let identity = WalletIdentity.generate()
        let token = try WalletSigner.issueAPIToken(identity: identity, validFor: -10)
        let payload = try WalletSigner.decodeTokenPayload(token)
        XCTAssertTrue(payload.isExpired)
    }

    func testTokenSignatureFailsVerificationIfPayloadIsTampered() throws {
        let identity = WalletIdentity.generate()
        let token = try WalletSigner.issueAPIToken(identity: identity, validFor: 3600)
        let parts = token.split(separator: ".").map(String.init)
        // Swap in a different (still well-formed) payload without re-signing.
        let otherIdentity = WalletIdentity.generate()
        let otherToken = try WalletSigner.issueAPIToken(identity: otherIdentity, validFor: 3600)
        let otherPayloadPart = otherToken.split(separator: ".").map(String.init)[0]

        let tampered = "\(otherPayloadPart).\(parts[1])"
        XCTAssertFalse(WalletSigner.verifyTokenSignature(tampered))
    }

    func testMalformedTokenFailsToDecode() {
        XCTAssertThrowsError(try WalletSigner.decodeTokenPayload("not-a-token"))
        XCTAssertFalse(WalletSigner.verifyTokenSignature("not-a-token"))
    }
}

private func dataFromHex(_ hex: String) -> Data? {
    guard hex.count % 2 == 0 else { return nil }
    var data = Data(capacity: hex.count / 2)
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
        data.append(byte)
        index = next
    }
    return data
}
