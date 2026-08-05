import Testing
import Foundation
@testable import AICoinWallet

struct WalletKeysTests {
    @Test func generatedWalletHasA64CharHexAddress() {
        let wallet = WalletKeys.generate()
        #expect(wallet.address.count == 64)
        #expect(wallet.address.allSatisfy { $0.isHexDigit })
    }

    @Test func backupBlobRoundTripsThroughImport() throws {
        let original = WalletKeys.generate()
        let blob = original.backupBlob
        #expect(blob.count == 128)

        let imported = try WalletKeys.importing(backupBlob: blob)
        #expect(imported.address == original.address)
        #expect(imported.seed == original.seed)
    }

    @Test func importRejectsWrongLength() {
        #expect(throws: WalletKeys.ImportError.self) {
            _ = try WalletKeys.importing(backupBlob: "abc")
        }
    }

    @Test func importRejectsNonHexCharacters() {
        let notHex = String(repeating: "zz", count: 64)
        #expect(throws: WalletKeys.ImportError.self) {
            _ = try WalletKeys.importing(backupBlob: notHex)
        }
    }

    @Test func importRejectsAMismatchedPublicKeyHalf() throws {
        let real = WalletKeys.generate()
        let tamperedAddress = String(repeating: "0", count: 64)
        let tampered = real.seed.hexString + tamperedAddress
        #expect(throws: WalletKeys.ImportError.self) {
            _ = try WalletKeys.importing(backupBlob: tampered)
        }
    }

    @Test func distinctGeneratedWalletsHaveDistinctAddresses() {
        let a = WalletKeys.generate()
        let b = WalletKeys.generate()
        #expect(a.address != b.address)
    }
}
