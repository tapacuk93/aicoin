import Foundation
import CryptoKit

/// Mirrors `WalletSignature.canonicalMessage`/live-signing and the token
/// scheme in the Java proxy (aicoin-proxy/src/main/java/com/aicoin/proxy/
/// WalletSignature.java) and the browser wallet's `signedFetch`/token
/// generation in wallet.html — byte-for-byte, since any drift here breaks
/// every signature silently on the server side.
enum WalletSigner {
    struct LiveSignatureHeaders {
        let address: String
        let signatureHex: String
        let timestampMillis: Int64

        var httpHeaders: [String: String] {
            [
                "X-Api-Key": address,
                "X-Api-Signature": signatureHex,
                "X-Api-Timestamp": String(timestampMillis)
            ]
        }
    }

    /// `address + "\n" + timestampMillis + "\n" + method + "\n" + path + "\n" + hex(sha256(body))`
    static func canonicalMessage(address: String, timestampMillis: Int64, method: String, path: String, body: Data) -> Data {
        let bodyHashHex = Data(SHA256.hash(data: body)).hexString
        let message = "\(address)\n\(timestampMillis)\n\(method)\n\(path)\n\(bodyHashHex)"
        return Data(message.utf8)
    }

    /// Signs a wallet-management request (claim/transfer/revoke-tokens) live, right now, with the given keys.
    static func signLiveRequest(keys: WalletKeys, method: String, path: String, body: Data) throws -> LiveSignatureHeaders {
        let timestampMillis = Int64((Date().timeIntervalSince1970 * 1000).rounded())
        let message = canonicalMessage(address: keys.address, timestampMillis: timestampMillis, method: method, path: path, body: body)
        let signature = try keys.privateKey.signature(for: message)
        return LiveSignatureHeaders(address: keys.address, signatureHex: Data(signature).hexString, timestampMillis: timestampMillis)
    }

    /// Builds a self-verifying API token: `base64url(payload).base64url(signature)`, signed once
    /// client-side. The signature covers the exact base64url payload string bytes (JWT-style),
    /// matching `WalletSignature.verifyToken`'s expectation server-side.
    /// - Parameter grant: the authorisation this token is issued under, when it
    ///   is issued by approving a service's request. A token naming a grant
    ///   stops working the moment that grant is revoked, which is the only way
    ///   one service can be cut off without cutting off every token this wallet
    ///   ever issued — a token is self-verifying, so nothing about the token
    ///   itself can be withdrawn. A token minted for a script names none, and
    ///   behaves exactly as tokens always have.
    static func buildToken(keys: WalletKeys, expiresInSeconds: Int, grant: String? = nil) throws -> String {
        let nowSeconds = Int(Date().timeIntervalSince1970)
        var payload = "{\"addr\":\"\(keys.address)\",\"iat\":\(nowSeconds),\"exp\":\(nowSeconds + expiresInSeconds)"
        if let grant {
            payload += ",\"grant\":\"\(grant)\""
        }
        payload += "}"
        let payloadB64 = Data(payload.utf8).base64URLEncodedString()
        let signature = try keys.privateKey.signature(for: Data(payloadB64.utf8))
        let signatureB64 = Data(signature).base64URLEncodedString()
        return "\(payloadB64).\(signatureB64)"
    }
}
