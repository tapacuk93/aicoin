import Foundation
import CryptoKit

/// A wallet identity: a real Ed25519 keypair, per CONTRACT.md's "Auth for wallet-management
/// actions" section. The **address** — used both to receive transfers and to identify the signer
/// of a request — is the hex-encoded raw 32-byte public key (64 hex chars). The private key never
/// leaves the device except inside a `backupBlob` the user explicitly asks to export.
///
/// This mirrors, field-for-field, the reference implementation in the standalone `AICoinWallet`
/// prototype app (`aicoin/ios/AICoinWallet`), whose own doc comments say it is written to match
/// the Java proxy's `WalletSignature.java` and the browser wallet's `wallet.html` byte-for-byte —
/// that makes it the most trustworthy source of truth for this exact scheme, so this type
/// generalizes it rather than re-deriving the format independently.
public struct WalletIdentity: Sendable, Equatable {
    /// The Ed25519 signing key. CryptoKit's `Curve25519.Signing.PrivateKey` produces raw 64-byte
    /// `R‖S` signatures (no DER wrapping) via `signature(for:)`, which is exactly what
    /// CONTRACT.md's `X-Api-Signature` expects and what the server verifies with Java's
    /// `Signature.verify(byte[])`.
    public let privateKey: Curve25519.Signing.PrivateKey

    public init(privateKey: Curve25519.Signing.PrivateKey) {
        self.privateKey = privateKey
    }

    /// Hex-encoded raw 32-byte public key — the wallet address, per CONTRACT.md.
    public var address: String {
        AICoinHex.encode(privateKey.publicKey.rawRepresentation)
    }

    /// Raw 32-byte private key seed, suitable for backup/export or reconstructing this identity
    /// later via `init(seed:)`.
    public var seed: Data {
        privateKey.rawRepresentation
    }

    /// Generates a brand-new, random Ed25519 keypair.
    public static func generate() -> WalletIdentity {
        WalletIdentity(privateKey: Curve25519.Signing.PrivateKey())
    }

    /// Reconstructs an identity from a raw 32-byte seed (as produced by `seed`).
    public init(seed: Data) throws {
        self.privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
    }

    public static func == (lhs: WalletIdentity, rhs: WalletIdentity) -> Bool {
        lhs.seed == rhs.seed
    }

    // MARK: - Backup

    /// 128 hex chars = 32-byte seed + 32-byte public key, concatenated. Storing both halves
    /// together (rather than just the seed) means a backup is self-verifying on import — the
    /// address half must match what the seed half re-derives — and portable with the browser
    /// wallet's own backup format, which does the same thing for the same reason (WebCrypto can't
    /// reliably re-derive an Ed25519 public key from a bare seed the way CryptoKit can).
    public var backupBlob: String {
        AICoinHex.encode(seed) + address
    }

    public enum ImportError: Error, LocalizedError, Sendable {
        case invalidLength
        case invalidHex
        case corrupted

        public var errorDescription: String? {
            switch self {
            case .invalidLength: return "Private key backup must be exactly 128 hex characters."
            case .invalidHex: return "Private key backup must contain only hex characters."
            case .corrupted: return "This backup key looks corrupted — the address half doesn't match the seed half."
            }
        }
    }

    /// Imports an identity from a `backupBlob`-shaped string, verifying the embedded address
    /// half actually matches what the seed half re-derives before accepting it.
    public static func importing(backupBlob raw: String) throws -> WalletIdentity {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 128 else { throw ImportError.invalidLength }
        guard let bytes = AICoinHex.decode(trimmed) else { throw ImportError.invalidHex }
        let seedBytes = bytes.prefix(32)
        let expectedAddress = AICoinHex.encode(bytes.suffix(32))
        let identity: WalletIdentity
        do {
            identity = try WalletIdentity(seed: Data(seedBytes))
        } catch {
            throw ImportError.corrupted
        }
        guard identity.address == expectedAddress else { throw ImportError.corrupted }
        return identity
    }
}

/// Internal-only hex helpers. Deliberately *not* exposed as public `Data` extensions: a public
/// `Data.hexString`/`Data.init(hexString:)` from this package could collide with an identically-
/// named extension a consuming app already declares on `Data` itself (each of the three apps'
/// prior-art code defines its own), producing "ambiguous use" errors at the call site. Everything
/// this package needs to expose publicly already comes back out as a typed `String` field (e.g.
/// `WalletIdentity.address`), so there's no need to widen `Data`'s own API surface.
enum AICoinHex {
    static func encode<D: DataProtocol>(_ data: D) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func decode(_ hexString: String) -> Data? {
        guard hexString.count % 2 == 0 else { return nil }
        var data = Data(capacity: hexString.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }
}

enum AICoinBase64URL {
    static func encode<D: DataProtocol>(_ data: D) -> String {
        Data(data).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decode(_ string: String) -> Data? {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64.append("=") }
        return Data(base64Encoded: base64)
    }
}
