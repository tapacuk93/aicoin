import Foundation
import CryptoKit

/// A wallet identity: an Ed25519 keypair. The address (used to receive
/// transfers and to identify AI-proxy calls) is the hex-encoded raw 32-byte
/// public key, exactly as the browser wallet and the proxy server define it
/// in CONTRACT.md. The private key never leaves the device except inside a
/// backup blob the user explicitly asks to see.
struct WalletKeys {
    let privateKey: Curve25519.Signing.PrivateKey

    var address: String {
        Data(privateKey.publicKey.rawRepresentation).hexString
    }

    var seed: Data {
        Data(privateKey.rawRepresentation)
    }

    static func generate() -> WalletKeys {
        WalletKeys(privateKey: Curve25519.Signing.PrivateKey())
    }

    init(privateKey: Curve25519.Signing.PrivateKey) {
        self.privateKey = privateKey
    }

    init(seed: Data) throws {
        privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
    }

    /// 128 hex chars = 32-byte seed + 32-byte public key, the same layout
    /// the browser wallet backs up (it stores both halves together since
    /// WebCrypto can't reliably re-derive a public key from a bare seed);
    /// CryptoKit *can* re-derive it, but keeping the same format means a
    /// backup blob is portable between the web wallet and this app.
    var backupBlob: String {
        seed.hexString + address
    }

    enum ImportError: LocalizedError {
        case invalidLength
        case invalidHex
        case corrupted

        var errorDescription: String? {
            switch self {
            case .invalidLength: return "Private key must be exactly 128 hex characters."
            case .invalidHex: return "Private key must contain only hex characters."
            case .corrupted: return "This backup key looks corrupted."
            }
        }
    }

    static func importing(backupBlob raw: String) throws -> WalletKeys {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count == 128 else { throw ImportError.invalidLength }
        guard let bytes = Data(hexString: trimmed) else { throw ImportError.invalidHex }
        let seed = bytes.prefix(32)
        let expectedAddress = bytes.suffix(32).hexString
        let wallet = try WalletKeys(seed: Data(seed))
        guard wallet.address == expectedAddress else { throw ImportError.corrupted }
        return wallet
    }
}

extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }

    init?(hexString: String) {
        guard hexString.count % 2 == 0 else { return nil }
        var data = Data(capacity: hexString.count / 2)
        var index = hexString.startIndex
        while index < hexString.endIndex {
            let next = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        self = data
    }

    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
