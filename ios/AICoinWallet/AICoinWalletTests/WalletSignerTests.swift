import Testing
import Foundation
import CryptoKit
@testable import AICoinWallet

struct WalletSignerTests {
    @Test func canonicalMessageMatchesTheServerAndBrowserWalletFormat() {
        let body = Data("{\"to_user_id\":\"abc\",\"amount\":1}".utf8)
        let message = WalletSigner.canonicalMessage(address: "addr", timestampMillis: 42, method: "POST", path: "/wallet/api/transfer", body: body)
        let bodyHashHex = Data(SHA256.hash(data: body)).hexString
        let expected = "addr\n42\nPOST\n/wallet/api/transfer\n\(bodyHashHex)"
        #expect(String(data: message, encoding: .utf8) == expected)
    }

    @Test func canonicalMessageOfAnEmptyBodyHashesTheEmptyString() {
        let message = WalletSigner.canonicalMessage(address: "addr", timestampMillis: 1, method: "POST", path: "/wallet/api/claim", body: Data())
        let emptyHashHex = Data(SHA256.hash(data: Data())).hexString
        #expect(String(data: message, encoding: .utf8) == "addr\n1\nPOST\n/wallet/api/claim\n\(emptyHashHex)")
    }

    @Test func liveSignatureVerifiesAgainstTheWalletsOwnPublicKey() throws {
        let keys = WalletKeys.generate()
        let headers = try WalletSigner.signLiveRequest(keys: keys, method: "POST", path: "/wallet/api/claim", body: Data())

        #expect(headers.address == keys.address)
        #expect(headers.signatureHex.count == 128)

        let message = WalletSigner.canonicalMessage(
            address: headers.address, timestampMillis: headers.timestampMillis,
            method: "POST", path: "/wallet/api/claim", body: Data())
        let signatureBytes = Data(hexString: headers.signatureHex)!
        #expect(keys.privateKey.publicKey.isValidSignature(signatureBytes, for: message))
    }

    @Test func tamperingWithTheSignedMessageBreaksVerification() throws {
        let keys = WalletKeys.generate()
        let headers = try WalletSigner.signLiveRequest(keys: keys, method: "POST", path: "/wallet/api/claim", body: Data())
        let tamperedMessage = WalletSigner.canonicalMessage(
            address: headers.address, timestampMillis: headers.timestampMillis,
            method: "POST", path: "/wallet/api/transfer", body: Data())
        let signatureBytes = Data(hexString: headers.signatureHex)!
        #expect(!keys.privateKey.publicKey.isValidSignature(signatureBytes, for: tamperedMessage))
    }

    @Test func tokenHasTheJwtShapedFormatAndVerifiesAgainstItsOwnPayload() throws {
        let keys = WalletKeys.generate()
        let token = try WalletSigner.buildToken(keys: keys, expiresInSeconds: 3600)
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        #expect(parts.count == 2)

        let payloadB64 = String(parts[0])
        let signatureB64 = String(parts[1])
        let signatureBytes = try #require(Data(base64URLEncoded: signatureB64))
        #expect(keys.privateKey.publicKey.isValidSignature(signatureBytes, for: Data(payloadB64.utf8)))

        let payloadBytes = try #require(Data(base64URLEncoded: payloadB64))
        let payload = try #require(try JSONSerialization.jsonObject(with: payloadBytes) as? [String: Any])
        #expect(payload["addr"] as? String == keys.address)
    }
}

private extension Data {
    init?(base64URLEncoded string: String) {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        self.init(base64Encoded: base64)
    }
}
