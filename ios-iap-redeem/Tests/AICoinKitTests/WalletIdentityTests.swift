import XCTest
@testable import AICoinKit

final class WalletIdentityTests: XCTestCase {
    func testGeneratedWalletHasA64CharLowercaseHexAddress() {
        let identity = WalletIdentity.generate()
        XCTAssertEqual(identity.address.count, 64)
        XCTAssertTrue(identity.address.allSatisfy { $0.isHexDigit && !$0.isUppercase })
    }

    func testDistinctGeneratedWalletsHaveDistinctAddresses() {
        let a = WalletIdentity.generate()
        let b = WalletIdentity.generate()
        XCTAssertNotEqual(a.address, b.address)
    }

    func testSeedRoundTripsThroughInitSeed() throws {
        let original = WalletIdentity.generate()
        let reconstructed = try WalletIdentity(seed: original.seed)
        XCTAssertEqual(reconstructed.address, original.address)
    }

    func testBackupBlobRoundTripsThroughImport() throws {
        let original = WalletIdentity.generate()
        let blob = original.backupBlob
        XCTAssertEqual(blob.count, 128)

        let imported = try WalletIdentity.importing(backupBlob: blob)
        XCTAssertEqual(imported.address, original.address)
        XCTAssertEqual(imported.seed, original.seed)
    }

    func testImportRejectsWrongLength() {
        XCTAssertThrowsError(try WalletIdentity.importing(backupBlob: "abc")) { error in
            XCTAssertEqual(error as? WalletIdentity.ImportError, .invalidLength)
        }
    }

    func testImportRejectsNonHexCharacters() {
        let notHex = String(repeating: "zz", count: 64)
        XCTAssertThrowsError(try WalletIdentity.importing(backupBlob: notHex)) { error in
            XCTAssertEqual(error as? WalletIdentity.ImportError, .invalidHex)
        }
    }

    func testImportRejectsAMismatchedAddressHalf() throws {
        let real = WalletIdentity.generate()
        let wrongAddress = String(repeating: "0", count: 64)
        let corrupted = real.backupBlob.prefix(64) + wrongAddress
        XCTAssertThrowsError(try WalletIdentity.importing(backupBlob: String(corrupted))) { error in
            XCTAssertEqual(error as? WalletIdentity.ImportError, .corrupted)
        }
    }
}
