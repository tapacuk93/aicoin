import Foundation
import CryptoKit

/// Produces the live-signed headers CONTRACT.md's "Auth for wallet-management actions" section
/// requires (`POST /wallet/api/claim`, `/transfer`, `/revoke-tokens`), and issues the self-verifying
/// API tokens its "Auth for AI-proxy calls" section describes.
///
/// Byte-for-byte generalization of the prototype `AICoinWallet` app's `WalletSigner`, whose own
/// doc comments say it mirrors the Java proxy's `WalletSignature.java` and the browser wallet's
/// `wallet.html` `signedFetch`/token generation exactly — any drift here silently breaks every
/// signature server-side, so this package treats that prototype as ground truth rather than
/// re-deriving the format from CONTRACT.md's prose alone.
public enum WalletSigner {
    /// The three headers a live-signed wallet-management request must carry.
    public struct LiveSignatureHeaders: Sendable, Equatable {
        public let address: String
        public let signatureHex: String
        public let timestampMillis: Int64

        public var httpHeaders: [String: String] {
            [
                "X-Api-Key": address,
                "X-Api-Signature": signatureHex,
                "X-Api-Timestamp": String(timestampMillis),
            ]
        }
    }

    /// The exact canonical message CONTRACT.md specifies:
    /// `address + "\n" + timestampMillis + "\n" + httpMethod + "\n" + requestPath + "\n" + hex(sha256(requestBody))`.
    /// `path` must have no query string (CONTRACT.md is explicit about this) — callers should pass
    /// `URLRequest.url?.path`, never `absoluteString` or anything containing a `?`.
    public static func canonicalMessage(
        address: String,
        timestampMillis: Int64,
        method: String,
        path: String,
        body: Data
    ) -> Data {
        let bodyHashHex = AICoinHex.encode(Data(SHA256.hash(data: body)))
        let message = "\(address)\n\(timestampMillis)\n\(method)\n\(path)\n\(bodyHashHex)"
        return Data(message.utf8)
    }

    /// Signs a wallet-management request (claim/transfer/revoke-tokens) live, right now, with
    /// `identity`'s private key. A fresh timestamp is generated on every call — these headers are
    /// single-use, per CONTRACT.md's replay-window semantics (`aicoin.signatureSkewSeconds`).
    public static func signLiveRequest(
        identity: WalletIdentity,
        method: String,
        path: String,
        body: Data,
        now: Date = Date()
    ) throws -> LiveSignatureHeaders {
        let timestampMillis = Int64((now.timeIntervalSince1970 * 1000).rounded())
        let message = canonicalMessage(
            address: identity.address, timestampMillis: timestampMillis, method: method, path: path, body: body
        )
        let signature = try identity.privateKey.signature(for: message)
        return LiveSignatureHeaders(
            address: identity.address, signatureHex: AICoinHex.encode(signature), timestampMillis: timestampMillis
        )
    }

    /// Signs `request` in place, setting `X-Api-Key`/`X-Api-Signature`/`X-Api-Timestamp` from
    /// `request`'s own method, URL path (query string excluded), and body (empty body if `nil`,
    /// matching the server's expectation that a bodyless request hashes the empty byte string).
    public static func applyLiveSignature(to request: inout URLRequest, identity: WalletIdentity, now: Date = Date()) throws {
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? "/"
        let body = request.httpBody ?? Data()
        let headers = try signLiveRequest(identity: identity, method: method, path: path, body: body, now: now)
        for (field, value) in headers.httpHeaders {
            request.setValue(value, forHTTPHeaderField: field)
        }
    }

    // MARK: - API tokens (CONTRACT.md "Auth for AI-proxy calls")

    /// The decoded, unverified contents of an API token's payload half.
    public struct TokenPayload: Codable, Sendable, Equatable {
        public let addr: String
        public let iat: Int
        public let exp: Int

        public var issuedAt: Date { Date(timeIntervalSince1970: TimeInterval(iat)) }
        public var expiresAt: Date { Date(timeIntervalSince1970: TimeInterval(exp)) }
        public var isExpired: Bool { Date() >= expiresAt }
    }

    /// Builds a self-verifying API token — `base64url(payloadJson).base64url(signature)` — per
    /// CONTRACT.md: "Issuance is entirely client-side ... the signature covers the exact
    /// `base64url(payload)` string bytes (sign the encoded form, not the raw JSON)". There is
    /// deliberately no server round-trip here; a token is minted purely by the private key.
    public static func issueAPIToken(identity: WalletIdentity, validFor: TimeInterval, now: Date = Date()) throws -> String {
        let iat = Int(now.timeIntervalSince1970)
        let exp = iat + Int(validFor)
        // Hand-built rather than `JSONEncoder` so the exact three keys/order are guaranteed and
        // no encoder ever needs to be trusted not to throw on these three known-safe scalar
        // fields — matches the prototype app's approach byte-for-byte.
        let payloadJSON = "{\"addr\":\"\(identity.address)\",\"iat\":\(iat),\"exp\":\(exp)}"
        let payloadB64 = AICoinBase64URL.encode(Data(payloadJSON.utf8))
        let signature = try identity.privateKey.signature(for: Data(payloadB64.utf8))
        let signatureB64 = AICoinBase64URL.encode(signature)
        return "\(payloadB64).\(signatureB64)"
    }

    public enum TokenDecodingError: Error, Sendable {
        case malformedShape
        case malformedPayload
    }

    /// Decodes a token's payload without verifying its signature — useful for local UI (e.g.
    /// showing a token's expiry) where the token is trusted because this device issued it. Never
    /// use this as an auth check; only the server's own signature verification is authoritative.
    public static func decodeTokenPayload(_ token: String) throws -> TokenPayload {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2, let payloadData = AICoinBase64URL.decode(String(parts[0])) else {
            throw TokenDecodingError.malformedShape
        }
        do {
            return try JSONDecoder().decode(TokenPayload.self, from: payloadData)
        } catch {
            throw TokenDecodingError.malformedPayload
        }
    }

    /// Verifies a token's signature against its own embedded address — the same self-verifying
    /// check the server performs, exposed here for tests and for defensive client-side sanity
    /// checks (e.g. before caching a freshly-issued token). Not required for correctness of any
    /// server call: the server always re-verifies independently.
    public static func verifyTokenSignature(_ token: String) -> Bool {
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 2,
              let payloadData = AICoinBase64URL.decode(String(parts[0])),
              let signatureData = AICoinBase64URL.decode(String(parts[1])),
              let payload = try? JSONDecoder().decode(TokenPayload.self, from: payloadData),
              let addressBytes = AICoinHex.decode(payload.addr)
        else { return false }

        guard let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: addressBytes) else { return false }
        return publicKey.isValidSignature(signatureData, for: Data(String(parts[0]).utf8))
    }
}
